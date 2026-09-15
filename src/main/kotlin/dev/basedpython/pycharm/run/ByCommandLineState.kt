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
     * Whether [ByCommonOptions.extraArgs] are for the program `by run` starts rather than for `by`.
     *
     * Only a configuration whose field says so — the test configuration's "Extra pytest args" —
     * sets this; everywhere else the field is `by`'s own flags. See [byArguments].
     */
    protected open val extraArgsForProgram: Boolean = false

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

    /**
     * Flags the infrastructure adds to the subcommand, written by the same hook and for the same
     * reason as [infrastructureEnv].
     *
     * A command line rather than more environment because the environment is no longer enough:
     * `by run` resolves the project's interpreter from the project itself and reads `$PYTHON` only
     * below that, so the bpd backend names its wrapper here as `--python` — the one place that
     * outranks discovery. See [dev.basedpython.pycharm.debug.bpd.ByBpdWrapper].
     *
     * These land *before* the positionals, which is load-bearing: `by run` forwards everything
     * after the module to the program, so a flag placed after it would be the program's argument
     * rather than `by`'s.
     */
    val infrastructureArgs: MutableList<String> = mutableListOf()

    /** Directories to put in front of `PYTHONPATH`; see [composePythonPath]. */
    val pythonPathPrefix: MutableList<String> = mutableListOf()

    /**
     * Whether this subcommand ends up running the transpiled program, and so needs the project's
     * own `.py` modules to be importable — see [byCommandLine].
     *
     * `run` does, and a test run *is* a `by run pytest`. `build` and `check` emit or inspect code
     * without ever starting an interpreter, so there is no `sys.path` to repair and no reason to
     * put a directory in front of a `PYTHONPATH` `by` itself reads.
     */
    protected open val startsProgram: Boolean get() = subcommandStartsProgram(subcommand)

    override fun startProcess(): ProcessHandler {
        val cmd = byCommandLine(
            project = project,
            options = options,
            arguments = buildCommand(),
            infrastructureEnv = infrastructureEnv,
            pythonPathPrefix = pythonPathPrefix,
            startsProgram = startsProgram,
        )
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
        infrastructureArgs = infrastructureArgs,
        extraArgsForProgram = extraArgsForProgram,
    )
}

/**
 * The `by` process a configuration with [options] launches for [arguments]: the `by` its
 * environment setting resolves, in its working directory, with its environment.
 *
 * Shared by every `by` configuration and by anything that runs `by` *on behalf of* one — the
 * "Run `by build` first" step builds with the environment of the configuration it precedes, so the
 * build and the run cannot disagree about which `by` or which variables they saw.
 *
 * [infrastructureEnv] is applied after the user's variables and [pythonPathPrefix] goes in front of
 * `PYTHONPATH`, as [ByCommandLineState.infrastructureEnv] and [ByCommandLineState.pythonPathPrefix]
 * describe. [startsProgram] puts the working directory on `PYTHONPATH` as well.
 *
 * @throws ExecutionException when no `by` can be found.
 */
internal fun byCommandLine(
    project: Project,
    options: ByCommonOptions,
    arguments: List<String>,
    infrastructureEnv: Map<String, String> = emptyMap(),
    pythonPathPrefix: List<String> = emptyList(),
    startsProgram: Boolean = false,
): GeneralCommandLine {
    val launch = BasedPythonBinaries.launchBy(project, kind = options.environmentKind)
        ?: throw ExecutionException(byNotFoundMessage(options.environmentKind))

    val cmd = GeneralCommandLine()
        .withExePath(launch.exe.toString())
        .withCharset(Charsets.UTF_8)

    // Empty for a plain venv launch; for uv this is `run --project <dir> by`.
    cmd.addParameters(launch.prependArgs)
    cmd.addParameters(arguments)

    val wd = FileUtil.toSystemDependentName(
        options.workingDir.ifBlank { project.basePath ?: System.getProperty("user.home") },
    )
    cmd.withWorkDirectory(wd)

    cmd.withParentEnvironmentType(
        if (options.passParentEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE
        else GeneralCommandLine.ParentEnvironmentType.NONE
    )
    // Activation first so a user-set variable of the same name still wins.
    cmd.withEnvironment(activationEnv(options, launch))
    // `by run` spawns a Python child whose stdout is a pipe, and CPython block-buffers a pipe:
    // a program's output appeared only when it exited, which is useless while stepping through
    // it in the debugger. Set before the user's own environment so it stays overridable.
    cmd.withEnvironment(PYTHONUNBUFFERED, "1")
    cmd.withEnvironment(options.envVars)
    cmd.withEnvironment(infrastructureEnv)
    // The working directory belongs on `PYTHONPATH`, behind whatever the debugger put there.
    //
    // `by run` transpiles into a temp directory and starts `<python> _by_runner.py` *there*, so
    // `sys.path[0]` is the temp tree rather than the project — and a plain `.py` is never
    // copied into that tree. A project mixing `helper.py` with `main.by` therefore died on
    // `ImportError: No module named 'helper'` before a debugger was ever in the picture, while
    // `by` itself resolved the same import happily when type checking. This restores what
    // `python main.py` would have given the program: the directory its sources are in.
    //
    // Behind the transpiled output, never in front of it: the temp tree stays `sys.path[0]`, so
    // a generated module still wins over a stale `.py` of the same name left lying beside the
    // source it was generated from.
    val prefixes = pythonPathPrefix + listOfNotNull(wd.takeIf { startsProgram })
    if (prefixes.isNotEmpty()) {
        cmd.withEnvironment(PYTHONPATH, composePythonPath(prefixes, inheritedPythonPath(options)))
    }
    return cmd
}

/**
 * The venv activation to apply.
 *
 * [ByLaunch.env] carries a `PATH` built on top of the IDE's own, which is right for an inherited
 * environment and wrong when the user unticked "pass parent environment" — that leaks the whole
 * IDE `PATH` back in through the explicit map. Rebuild against an empty parent in that case, so a
 * hermetic run gets the venv's bin directory and nothing else.
 */
private fun activationEnv(options: ByCommonOptions, launch: ByLaunch): Map<String, String> {
    if (options.passParentEnv) return launch.env
    val venv = launch.venvRoot ?: return launch.env
    return ByEnvironments.activationEnv(venv, parentPath = null)
}

/**
 * The `PYTHONPATH` a prefix has to be prepended to.
 *
 * A user-set value wins, then the IDE's own — but only when the run inherits the parent
 * environment. A hermetic run has no inherited `PYTHONPATH` to extend, and pulling the IDE's
 * back in here would be the same leak [activationEnv] avoids for `PATH`.
 */
private fun inheritedPythonPath(options: ByCommonOptions): String? =
    options.envVars[PYTHONPATH]
        ?: if (options.passParentEnv) EnvironmentUtil.getValue(PYTHONPATH) else null

private fun byNotFoundMessage(kind: ByEnvironmentKind): String =
    if (kind == ByEnvironmentKind.AUTO) {
        "by binary not found — set path in Settings | basedpython"
    } else {
        "by binary not found via ${kind.display} — change the Environment " +
            "setting of this run configuration, or set a path in Settings | basedpython"
    }

/**
 * Whether [subcommand] ends up starting an interpreter on the transpiled output.
 *
 * `run` is the only one, and a test run is not an exception to that but an instance of it: the test
 * configuration invokes `by run pytest -v`. `build` writes python, `check` type-checks it, and
 * neither runs anything.
 */
internal fun subcommandStartsProgram(subcommand: String): Boolean = subcommand == "run"

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
 *
 * [infrastructureArgs] are the debugger's own flags. They go with the version flag, ahead of the
 * positionals, because `by run` forwards everything after the module to the program — a
 * `--python` behind it would reach the debuggee as an argument instead of `by` as an option.
 *
 * [extraArgs] go there too, for the same reason: they are `by`'s flags, and `by run main --soundness
 * none` hands `--soundness none` to `main`. Only when [extraArgsForProgram] says they belong to the
 * program (`by run pytest -v … -k name`) do they follow the positionals.
 */
internal fun byArguments(
    subcommand: String,
    pythonVersionFlag: String?,
    pythonVersion: String,
    subcommandArgs: List<String>,
    extraArgs: String,
    infrastructureArgs: List<String> = emptyList(),
    extraArgsForProgram: Boolean = false,
): List<String> = buildList {
    val extra = if (extraArgs.isBlank()) emptyList() else ParametersListUtil.parse(extraArgs)
    add(subcommand)
    val version = pythonVersion.trim()
    if (pythonVersionFlag != null && version.isNotEmpty()) {
        add(pythonVersionFlag)
        add(version)
    }
    addAll(infrastructureArgs)
    if (!extraArgsForProgram) addAll(extra)
    addAll(subcommandArgs)
    if (extraArgsForProgram) addAll(extra)
}
