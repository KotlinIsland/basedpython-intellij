package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ergonomics.ByConsoleFilter
import dev.basedpython.pycharm.run.target.ByTargetLaunch
import dev.basedpython.pycharm.run.target.ByTargetPaths
import dev.basedpython.pycharm.run.target.byTargetCommandLine
import dev.basedpython.pycharm.util.BasedPythonBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile
import com.intellij.execution.target.TargetEnvironmentAwareRunProfileState
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.TargetProgressIndicator
import com.intellij.execution.target.getEffectiveTargetName
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.nio.file.Path

private const val PYTHONPATH = "PYTHONPATH"
private const val PYTHONUNBUFFERED = "PYTHONUNBUFFERED"

private val LOG = Logger.getInstance(ByCommandLineState::class.java)

/**
 * Shared `CommandLineState` for all `by` configurations. Subclasses name the [subcommand] and
 * provide its positional args via [buildSubcommandArgs]; the base handles binary resolution, the
 * Python-version flag, working dir, env vars, and process listener wiring.
 *
 * It also runs the command on a run target, when the configuration is one that can be given a
 * target (a [TargetEnvironmentAwareRunProfile]) and has been: the platform hands the request for the
 * target to [prepareTargetEnvironmentRequest] and the target it made to
 * [handleCreatedTargetEnvironment], and [startProcess] then starts the same `by` arguments there
 * instead of here. What drives those steps is [dev.basedpython.pycharm.run.target.ByTargetProgramRunner].
 */
abstract class ByCommandLineState(
    protected val project: Project,
    protected val options: ByCommonOptions,
    environment: ExecutionEnvironment,
) : CommandLineState(environment), TargetEnvironmentAwareRunProfileState {

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
     * A command line rather than more environment because the environment is not enough: the bpd
     * backend puts its wrapper in front of the interpreter `by run` chooses, and `--launcher` is
     * the only way to name one. See [dev.basedpython.pycharm.debug.bpd.ByBpdWrapper].
     *
     * These land *before* the positionals, which is load-bearing: `by run` forwards everything
     * after the module to the program, so a flag placed after it would be the program's argument
     * rather than `by`'s.
     */
    val infrastructureArgs: MutableList<String> = mutableListOf()

    /**
     * Listeners the infrastructure needs on the process from its very first byte, written by the
     * same hook as [infrastructureEnv] and added before the process is started — a listener added
     * afterwards can miss what a short-lived process wrote.
     */
    val infrastructureListeners: MutableList<ProcessListener> = mutableListOf()

    /**
     * Whether a request to end the process comes after what it ran has already ended — written by
     * the same hook as [infrastructureEnv], for the debug session the process runs under, and read
     * by [ByProcessHandler]: such a request leaves `by` to exit with the program's code rather than
     * killing it. Never, for a run nothing else watches.
     */
    var finishesOnItsOwn: () -> Boolean = { false }

    /**
     * Variables the configuration sets for whatever reads the run's output, here or on a run target:
     * a test run's `BY_TEST_REPORTER`, which has `by run pytest` report to the IDE's test tree. Applied
     * after [ByCommonOptions.envVars], since the console is built for this output and no other.
     */
    protected open val runnerEnvironment: Map<String, String> get() = emptyMap()

    /** Directories to put in front of `PYTHONPATH`; see [composePythonPath]. */
    val pythonPathPrefix: MutableList<String> = mutableListOf()

    /**
     * Variables read from the configuration's `.env` files, which the user's own variables win over;
     * read when the run starts, so an edit to a file is seen by the next run.
     *
     * @throws ExecutionException when a file cannot be read.
     */
    protected open fun fileEnvironment(): Map<String, String> = emptyMap()

    /** The file the program reads its standard input from, rather than the console; `null` for the console. */
    protected open val inputFile: File? get() = null

    /**
     * Whether the program runs in a pseudo-terminal, its output shown in a terminal console — what
     * reads a key at a time, draws a progress bar or asks `isatty()` sees a terminal.
     */
    protected open val emulateTerminal: Boolean get() = false

    /**
     * Whether this subcommand ends up running the transpiled program, and so needs the project's
     * own `.py` modules to be importable — see [byCommandLine].
     *
     * `run` does, and a test run *is* a `by run pytest`. `build` and `check` emit or inspect code
     * without ever starting an interpreter, so there is no `sys.path` to repair and no reason to
     * put a directory in front of a `PYTHONPATH` `by` itself reads.
     */
    protected open val startsProgram: Boolean get() = subcommandStartsProgram(subcommand)

    /** Set while a target is being prepared for this run; see [prepareTargetEnvironmentRequest]. */
    private var targetLaunch: ByTargetLaunch? = null

    /** Where the project landed on the run target once it exists; see [handleCreatedTargetEnvironment]. */
    private var targetPaths: ByTargetPaths? = null

    /**
     * Whether the configuration asked for a run target rather than this machine.
     *
     * Read from the configuration rather than from whether a target was prepared, so a launch path
     * that never prepared one fails in [startProcess] instead of quietly running here what the user
     * said to run elsewhere.
     */
    private val runsOnTarget: Boolean
        get() = (environment.runProfile as? TargetEnvironmentAwareRunProfile)?.getEffectiveTargetName(project) != null

    override fun prepareTargetEnvironmentRequest(request: TargetEnvironmentRequest, targetProgressIndicator: TargetProgressIndicator) {
        // a local request has no configuration: nothing to upload and nothing to map
        val target = request.configuration ?: return
        val root = project.basePath ?: throw ExecutionException(BasedPythonBundle.message("target.error.noProjectDir"))
        targetLaunch = ByTargetLaunch(target, Path.of(root)).also { it.prepare(request) }
    }

    override fun handleCreatedTargetEnvironment(targetEnvironment: TargetEnvironment, targetProgressIndicator: TargetProgressIndicator) {
        targetPaths = targetLaunch?.created(targetEnvironment, targetProgressIndicator)
    }

    override fun startProcess(): ProcessHandler {
        if (runsOnTarget) return startOnTarget()
        val cmd = byCommandLine(
            project = project,
            options = options,
            arguments = buildCommand(),
            infrastructureEnv = runnerEnvironment + infrastructureEnv,
            pythonPathPrefix = pythonPathPrefix,
            startsProgram = startsProgram,
            fileEnvironment = fileEnvironment(),
        )
        inputFile?.let(cmd::withInput)
        val handler = ByProcessHandler(if (emulateTerminal) PtyCommandLine(cmd).withConsoleMode(false) else cmd, finishesOnItsOwn)
        // Stop has to reach the program, not just the launcher. `by run` is a Rust parent that
        // spawns `python _by_runner.py` and then blocks waiting for it, and a SIGINT to `by`
        // demonstrably kills neither — both survive, and under a debugger the orphaned interpreter
        // keeps holding its port. So: no soft kill (it achieves nothing here but a delay), and
        // destroy the whole tree rather than the one process the IDE happens to hold.
        handler.setShouldKillProcessSoftly(false)
        handler.setShouldDestroyProcessRecursively(true)
        infrastructureListeners.forEach(handler::addProcessListener)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    /**
     * Starts `by` on the run target [handleCreatedTargetEnvironment] was given, with the arguments a
     * local run would have, in the working directory's counterpart there.
     *
     * The debugger's additions are refused rather than carried over: [infrastructureEnv],
     * [infrastructureArgs] and [pythonPathPrefix] name files and ports on this machine — a bootstrap
     * directory, a wrapper script, a port only `localhost` listens on — none of which exist on the
     * target, so a program started with them would wait for a debugger that can never reach it.
     */
    private fun startOnTarget(): ProcessHandler {
        if (infrastructureEnv.isNotEmpty() || infrastructureArgs.isNotEmpty() || pythonPathPrefix.isNotEmpty()) {
            throw ExecutionException(BasedPythonBundle.message("target.error.onlyRun"))
        }
        // both are this machine's: a file here, and a terminal console attached to a local pty
        if (inputFile != null || emulateTerminal) throw ExecutionException(BasedPythonBundle.message("target.error.localOnlyOptions"))
        val launch = targetLaunch
        val paths = targetPaths
        if (launch == null || paths == null) throw ExecutionException(BasedPythonBundle.message("target.error.notPrepared"))
        val target = environment.getPreparedTargetEnvironment(this, TargetProgressIndicator.EMPTY)

        val localWorkDir = Path.of(options.workingDir.ifBlank { paths.localRoot.toString() })
        val workDir = paths.toTarget(localWorkDir) ?: throw ExecutionException(
            BasedPythonBundle.message("target.error.workingDirOutsideProject", localWorkDir, paths.localRoot),
        )
        val commandLine = byTargetCommandLine(
            request = target.request,
            byPath = launch.byPath,
            arguments = buildCommand(),
            workDirectory = workDir,
            environment = byTargetEnvironment(options, workDir, startsProgram, paths.platform.pathSeparator, fileEnvironment()) +
                runnerEnvironment,
        )
        val process = target.createProcess(commandLine, EmptyProgressIndicator())
        val handler = KillableColoredProcessHandler(process, commandLine.getCommandPresentation(target), commandLine.charset)
        // the process is the target's, not a tree on this machine, so there is no soft signal to send
        // first or local tree to walk: destroying it is the target's own stop
        handler.setShouldKillProcessSoftly(false)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                // what the target set up for this run goes with it
                ApplicationManager.getApplication().executeOnPooledThread {
                    runCatching { target.shutdown() }.onFailure { LOG.info("could not shut the run target down", it) }
                }
            }
        })
        infrastructureListeners.forEach(handler::addProcessListener)
        ProcessTerminatedListener.attach(handler)
        // what the program prints names the target's files; this links them to the project's
        addConsoleFilters(ByConsoleFilter(project, paths::toLocal))
        return handler
    }

    /** A terminal console for a run that [emulateTerminal]s, attached to its pty as any console is. */
    override fun createConsole(executor: Executor): ConsoleView? =
        if (emulateTerminal) TerminalExecutionConsoleBuilder(project).build() else super.createConsole(executor)

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
 * The environment `by` gets on a run target: the user's variables, unbuffered output, and — when it
 * starts a program — the working directory on `PYTHONPATH`, for the reasons [byCommandLine] gives.
 *
 * No venv activation: the venv [byCommandLine] activates is one on this machine, and `by run` on the
 * target chooses the target's interpreter itself, as it does here when nothing is activated. The
 * user's `PYTHONPATH` is taken as written, in the target's terms, and joined with the target's
 * [separator].
 */
internal fun byTargetEnvironment(
    options: ByCommonOptions,
    workDirectory: String,
    startsProgram: Boolean,
    separator: Char,
    fileEnvironment: Map<String, String> = emptyMap(),
): Map<String, String> = buildMap {
    put(PYTHONUNBUFFERED, "1")
    putAll(fileEnvironment)
    putAll(options.envVars)
    if (startsProgram) {
        put(PYTHONPATH, composePythonPath(listOf(workDirectory), options.envVars[PYTHONPATH], separator))
    }
}

/**
 * The `by` process a configuration with [options] launches for [arguments]: the `by` its
 * environment setting resolves, in its working directory, with its environment.
 *
 * Shared by every `by` configuration and by anything that runs `by` *on behalf of* one — the
 * "Run `by build` first" step builds with the environment of the configuration it precedes, so the
 * build and the run cannot disagree about which `by` or which variables they saw.
 *
 * [fileEnvironment], the variables of the configuration's `.env` files, is applied before the user's
 * variables, [infrastructureEnv] after them, and [pythonPathPrefix] goes in front of
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
    fileEnvironment: Map<String, String> = emptyMap(),
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
    // a configuration's `.env` files, beneath the variables it sets itself
    cmd.withEnvironment(fileEnvironment)
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
 *
 * [separator] is the separator of the machine the program runs on, which for a run target is not
 * necessarily the IDE's.
 */
internal fun composePythonPath(
    prefixes: List<String>,
    existing: String?,
    separator: Char = File.pathSeparatorChar,
): String =
    (prefixes + (existing?.split(separator) ?: emptyList()))
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(separator.toString())

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
 * `--launcher` behind it would reach the debuggee as an argument instead of `by` as an option.
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
