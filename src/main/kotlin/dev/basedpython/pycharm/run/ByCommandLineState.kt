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
import com.intellij.util.execution.ParametersListUtil

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
        cmd.withEnvironment(options.envVars)

        val handler = KillableColoredProcessHandler(cmd)
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

    private fun notFoundMessage(): String =
        if (options.environmentKind == ByEnvironmentKind.AUTO) {
            "by binary not found — set path in Settings | basedpython"
        } else {
            "by binary not found via ${options.environmentKind.display} — change the Environment " +
                "setting of this run configuration, or set a path in Settings | basedpython"
        }
}

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
