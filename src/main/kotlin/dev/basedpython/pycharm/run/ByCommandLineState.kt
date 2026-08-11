package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File

private const val PYTHONPATH = "PYTHONPATH"
private const val PYTHONUNBUFFERED = "PYTHONUNBUFFERED"

/**
 * Shared `CommandLineState` for all `by` configurations. Subclasses name the [subcommand] and
 * provide its positional args via [buildSubcommandArgs]; the base handles binary resolution, the
 * Python-version flag, working dir, env vars, and process listener wiring.
 */
abstract class ByCommandLineState(
    protected val project: Project,
    protected val options: ByCommonOptions,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    /** The `by` subcommand this configuration runs, e.g. `run`. */
    protected abstract val subcommand: String

    /**
     * How this subcommand spells the Python version, or null when it takes no such flag.
     *
     * `run`, `build` and `transpile` take `--min-version` (the oldest interpreter the emitted code
     * must run on); `check` instead takes `--python-version` (the version to assume while resolving
     * types). Every one of them is a *subcommand* option, never a global one — see [buildCommand].
     */
    protected open val pythonVersionFlag: String? = "--min-version"

    /** Positional arguments and flags that follow [subcommand], e.g. `["pkg.mod"]`. */
    protected abstract fun buildSubcommandArgs(): List<String>

    /**
     * Environment set by the infrastructure around the run rather than by the user, applied after
     * [ByCommonOptions.envVars] so it cannot be shadowed by a stale project setting.
     *
     * Written by [dev.basedpython.pycharm.debug.ByDebugAdapterDescriptor] from
     * `DebugAdapterDescriptor.configureProfileState`, which the DAP runner calls after building
     * this state and before executing it — the platform's hook for "this process needs extra
     * parameters for a debugger to connect".
     */
    val infrastructureEnv: MutableMap<String, String> = linkedMapOf()

    /** Directories to put in front of `PYTHONPATH`; see [composePythonPath]. */
    val pythonPathPrefix: MutableList<String> = mutableListOf()

    override fun startProcess(): ProcessHandler {
        val launch = BasedPythonBinaries.launchBy(project, kind = options.environmentKind)
            ?: throw ExecutionException(notFoundMessage())

        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            .withCharset(Charsets.UTF_8)

        // Empty for a plain venv launch; for uv this is `run --project <dir> by`.
        cmd.addParameters(launch.prependArgs)
        cmd.addParameters(buildCommand())

        val wd = options.workingDir.ifBlank { project.basePath ?: System.getProperty("user.home") }
        cmd.withWorkDirectory(FileUtil.toSystemDependentName(wd))

        cmd.withParentEnvironmentType(
            if (options.passParentEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else GeneralCommandLine.ParentEnvironmentType.NONE
        )
        // Activation first so a user-set variable of the same name still wins.
        cmd.withEnvironment(activationEnv(launch))
        // `by run` spawns a Python child whose stdout is a pipe, and CPython block-buffers a pipe:
        // a program's output appeared only when it exited, which is useless while stepping through
        // it in the debugger. Set before the user's own environment so it stays overridable.
        cmd.withEnvironment(PYTHONUNBUFFERED, "1")
        cmd.withEnvironment(options.envVars)
        cmd.withEnvironment(infrastructureEnv)
        if (pythonPathPrefix.isNotEmpty()) {
            cmd.withEnvironment(PYTHONPATH, composePythonPath(pythonPathPrefix, inheritedPythonPath()))
        }

        val handler = KillableColoredProcessHandler(cmd)
        // Stop has to reach the program, not just the launcher. `by run` is a Rust parent that
        // spawns `python _by_runner.py` and then blocks waiting for it, and a SIGINT to `by`
        // demonstrably kills neither — both survive, and under a debugger the orphaned interpreter
        // keeps holding its port. So: no soft kill (it achieves nothing here but a delay), and
        // destroy the whole tree rather than the one process the IDE happens to hold.
        handler.setShouldKillProcessSoftly(false)
        handler.setShouldDestroyProcessRecursively(true)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    /** Everything after the executable, as [byArguments] assembles it. */
    internal fun buildCommand(): List<String> = byArguments(
        subcommand = subcommand,
        pythonVersionFlag = pythonVersionFlag,
        pythonVersion = options.pythonVersion,
        subcommandArgs = buildSubcommandArgs(),
        extraArgs = options.extraArgs,
    )

    /**
     * The venv activation to apply.
     *
     * [ByLaunch.env] carries a `PATH` built on top of the IDE's own, which is right for an inherited
     * environment and wrong when the user unticked "pass parent environment" — that leaks the whole
     * IDE `PATH` back in through the explicit map. Rebuild against an empty parent in that case, so a
     * hermetic run gets the venv's bin directory and nothing else.
     */
    private fun activationEnv(launch: ByLaunch): Map<String, String> {
        if (options.passParentEnv) return launch.env
        val venv = launch.venvRoot ?: return launch.env
        return ByEnvironments.activationEnv(venv, parentPath = null)
    }

    /**
     * The `PYTHONPATH` [pythonPathPrefix] has to be prepended to.
     *
     * A user-set value wins, then the IDE's own — but only when the run inherits the parent
     * environment. A hermetic run has no inherited `PYTHONPATH` to extend, and pulling the IDE's
     * back in here would be the same leak [activationEnv] avoids for `PATH`.
     */
    private fun inheritedPythonPath(): String? =
        options.envVars[PYTHONPATH]
            ?: if (options.passParentEnv) EnvironmentUtil.getValue(PYTHONPATH) else null

    private fun notFoundMessage(): String =
        if (options.environmentKind == ByEnvironmentKind.AUTO) {
            "by binary not found — set path in Settings | basedpython"
        } else {
            "by binary not found via ${options.environmentKind.display} — change the Environment " +
                "setting of this run configuration, or set a path in Settings | basedpython"
        }
}

/**
 * `PYTHONPATH` with [prefixes] in front of whatever the run already had.
 *
 * Prepending rather than replacing matters: `by run` passes its environment straight through to the
 * interpreter, and a project that sets `PYTHONPATH` to reach its own sources would stop importing
 * if the debugger's bootstrap directory overwrote it. A blank or absent [existing] contributes
 * nothing rather than a trailing separator, which CPython would read as "the current directory".
 */
internal fun composePythonPath(prefixes: List<String>, existing: String?): String =
    (prefixes + (existing?.split(File.pathSeparatorChar) ?: emptyList()))
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(File.pathSeparator)

/**
 * The `by` argument list for one subcommand: the subcommand, its own flags, then its positionals.
 *
 * `by` is a `clap` multi-command binary, so an option belonging to a subcommand has to *follow*
 * that subcommand — `by run --min-version 3.14 main`. Putting it first yields
 * `error: unexpected argument '--min-version' found`, which is what a run configuration with a
 * Python version set used to produce.
 *
 * A blank [pythonVersion], or a null [pythonVersionFlag] for a subcommand that takes no such flag,
 * emits nothing.
 */
internal fun byArguments(
    subcommand: String,
    pythonVersionFlag: String?,
    pythonVersion: String,
    subcommandArgs: List<String>,
    extraArgs: String,
): List<String> = buildList {
    add(subcommand)
    val version = pythonVersion.trim()
    if (pythonVersionFlag != null && version.isNotEmpty()) {
        add(pythonVersionFlag)
        add(version)
    }
    addAll(subcommandArgs)
    if (extraArgs.isNotBlank()) addAll(ParametersListUtil.parse(extraArgs))
}
