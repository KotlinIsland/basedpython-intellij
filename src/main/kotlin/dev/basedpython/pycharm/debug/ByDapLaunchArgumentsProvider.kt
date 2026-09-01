package dev.basedpython.pycharm.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.LaunchRequestArguments
import com.intellij.execution.ExecutionException
import dev.basedpython.pycharm.debug.bpd.ByBpdExecutable
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.runSubcommandArgs
import dev.basedpython.pycharm.run.test.ByPytest
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Declares which run configurations can be debugged, and prepares the session while doing it.
 *
 * `by run` and test configurations, and only under the Debug executor. The executor check is
 * load-bearing rather than defensive: `DapProgramRunner.canRun` accepts the *Run* executor too for
 * any profile some provider claims, so answering `true` there would route ordinary runs through the
 * debug adapter as well.
 *
 * Tests come along for free because a test run *is* a `by run`: the configuration invokes
 * `by run pytest -v`, so the same bootstrap reaches the same interpreter and the same source maps
 * describe the same transpiled tree. `by build` and `by check` are absent because neither produces
 * a running program to attach to.
 *
 * [getLaunchArguments] is the earliest hook in a DAP start, and the port has to exist by then to go
 * into the `attach` arguments — so this is also where [ByDebugSetup] is created. It travels to
 * [ByDebugAdapterDescriptor] on the run profile's user data, which the descriptor consumes and
 * clears in `configureProfileState` moments later in the same start.
 */
class ByDapLaunchArgumentsProvider : DapLaunchArgumentsProvider {

    override fun isApplicable(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            (profile is ByRunConfiguration || profile is ByTestConfiguration)

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        val setup = prepare(project)
        (profile as? UserDataHolder)?.putUserData(ByDebugSetup.KEY, setup)
        return when (setup.backend) {
            // debugpy's adapter is *in* the debuggee: the bootstrap called `debugpy.listen()` and
            // the IDE is the one connecting. `connect` is how its adapter spells that
            ByDebugBackend.DEBUGPY -> LaunchRequestArguments(
                adapterId = ByDebugAdapter,
                request = DapStartRequest.Attach,
                arguments = mapOf("connect" to mapOf("host" to "127.0.0.1", "port" to setup.port)),
            )

            // bpd is a debug adapter that starts programs, so this is a real launch. The program is
            // `_by_runner.py` **relative to bpd's own working directory**, which is the temp tree
            // `by run` transpiled into — the wrapper stands in that tree before it starts bpd, bpd
            // inherits the directory and the debuggee inherits it from bpd. That is what lets the
            // IDE name a program it cannot know the path of.
            //
            // Every key here is one `bpd_dap::Configuration` reads
            ByDebugBackend.BPD -> LaunchRequestArguments(
                adapterId = ByDebugAdapter,
                request = DapStartRequest.Launch,
                arguments = mapOf(
                    "program" to BY_RUNNER,
                    "args" to runnerArguments(profile),
                    "python" to (setup.python ?: DEFAULT_PYTHON),
                    // The IDE stops on its own breakpoints. Holding at the first statement of a
                    // runner shim nobody wrote would be a stop with no question behind it
                    "stopOnEntry" to false,
                ),
            )
        }
    }

    /**
     * Which backend this project debugs with, and everything that choice needs.
     *
     * A `bpd` that cannot be found is refused *here*, before the program starts, because this is
     * the last moment where refusing costs nothing. Falling back to debugpy silently would be
     * worse than either: the user chose a debugger and would get a different one.
     */
    @Throws(ExecutionException::class)
    private fun prepare(project: Project): ByDebugSetup {
        val settings = BasedPythonSettings.getInstance(project)
        return when (settings.debugBackend) {
            ByDebugBackend.DEBUGPY -> ByDebugSetup.create()
            ByDebugBackend.BPD -> {
                val launch = ByEnvironments.resolve(project, BY_BINARY)
                val bpd = ByBpdExecutable.resolve(launch)
                    ?: throw ExecutionException(BasedPythonBundle.message("debug.bpd.error.notFound"))
                ByDebugSetup.forBpd(bpd, interpreterOf(project, launch))
            }
        }
    }

    /**
     * The interpreter the debuggee runs on: the one the project's environment holds.
     *
     * The wrapper passes `by run`'s version probe through to this, and bpd starts the program with
     * it, so getting it wrong is not cosmetic — bpd needs PEP 669 and refuses anything below 3.13.
     *
     * It used to read `PYTHON` off the launch and fall back to `python3`, and that fallback was
     * always the answer: [dev.basedpython.pycharm.env.ByEnvironments.activationEnv] sets
     * `VIRTUAL_ENV`, `PATH` and `PYTHONHOME` and has never set `PYTHON`. It worked only because
     * the venv's `bin` is first on the `PATH` it does set, so the bare name happened to resolve
     * inside the venv — and where it did not, the session got the machine's `python3`, which is
     * routinely a 3.9 that bpd cannot debug at all. Naming the venv's own interpreter is the same
     * answer without the coincidence.
     *
     * `PYTHON` is still read first, so an explicitly configured interpreter still wins, and
     * `python3` remains the last resort for a launch backed by no venv at all.
     */
    private fun interpreterOf(project: Project, launch: ByLaunch?): String {
        launch?.env?.get("PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
        launch?.venvRoot?.let { return ByEnvironments.venvBinary(it, PYTHON_BINARY).toString() }
        if (launch?.kind == ByEnvironmentKind.UV) {
            uvInterpreter(project)?.let { return it.toString() }
        }
        return DEFAULT_PYTHON
    }

    /**
     * The interpreter uv runs this project's code on.
     *
     * **A uv launch names no venv**, and that is deliberate rather than an omission: it runs
     * everything through `uv run`, which establishes the environment itself, so
     * [ByLaunch.venvRoot] is null and [ByLaunch.env] is empty on purpose — see
     * [dev.basedpython.pycharm.env.ByEnvironments.resolve]. Every other consumer wants exactly
     * that. This one cannot use it: the wrapper `by run` probes has to `exec` a real interpreter,
     * and there is no path in a launch that is a command line.
     *
     * So the environment is named the way uv names it — `UV_PROJECT_ENVIRONMENT` when it is set,
     * and `.venv` beside the project otherwise, which is uv's default. Only returned when the
     * interpreter is really there; a guess that is not would put the session back on
     * [DEFAULT_PYTHON] by a longer route.
     *
     * Without this a uv-backed project — which is what a basedpython project normally is — reached
     * [DEFAULT_PYTHON], and `by run` was handed a wrapper that answered its version probe with
     * whatever `python3` means on the machine. That is a 3.9 here, which `by run` refuses outright;
     * on a machine where `python3` is new enough it would have been worse, because the program
     * would have *run*, on an interpreter nobody chose.
     */
    private fun uvInterpreter(project: Project): Path? {
        val base = project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() } ?: return null
        val configured = System.getenv("UV_PROJECT_ENVIRONMENT")?.takeIf { it.isNotBlank() }
        val root = configured?.let { base.resolve(it) } ?: base.resolve(UV_DEFAULT_ENVIRONMENT)
        return ByEnvironments.venvBinary(root, PYTHON_BINARY).takeIf { Files.isExecutable(it) }
    }

    /**
     * What `by run` would have put after the runner: the module, then the user's own arguments.
     *
     * Taken from the configuration rather than from the wrapper's record, because the launch
     * request is built before `by run` has started. The wrapper records what it was really asked
     * to run and [ByDebugAdapterDescriptor] checks the two agree, so a drift between this and
     * `by run` is reported rather than debugged.
     */
    private fun runnerArguments(profile: RunProfile): List<String> = when (profile) {
        is ByRunConfiguration -> runSubcommandArgs(profile.options)
        // a test run *is* a `by run`: the configuration invokes `by run pytest …`, so the same
        // runner shim starts the same way with pytest's arguments after it
        is ByTestConfiguration -> ByPytest.arguments(profile.options.paths)
        else -> emptyList()
    }

    private companion object {
        /** The shim `by run` writes into its build directory and starts the program through. */
        private const val BY_RUNNER = "_by_runner.py"

        /** The last resort, for a launch backed by no environment to name one from. */
        private const val DEFAULT_PYTHON = "python3"

        /** The interpreter's name inside a venv's `bin` / `Scripts`. */
        private const val PYTHON_BINARY = "python"

        /** Where uv puts a project's environment when nothing says otherwise. */
        private const val UV_DEFAULT_ENVIRONMENT = ".venv"

        /** The binary whose directory a sibling `bpd` is looked for in. */
        private const val BY_BINARY = "by"
    }
}
