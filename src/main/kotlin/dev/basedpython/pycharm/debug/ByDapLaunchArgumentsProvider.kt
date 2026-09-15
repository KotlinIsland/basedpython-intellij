package dev.basedpython.pycharm.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.LaunchRequestArguments
import com.intellij.execution.ExecutionException
import dev.basedpython.pycharm.debug.bpd.ByBpdExecutable
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByConfiguration
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path

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
 * [ByDebugAdapterDescriptor] through [ByDebugSetups], which the descriptor takes it from in
 * `configureProfileState` moments later in the same start.
 */
class ByDapLaunchArgumentsProvider : DapLaunchArgumentsProvider {

    override fun isApplicable(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            (profile is ByRunConfiguration || profile is ByTestConfiguration)

    override fun getLaunchArguments(project: Project, profile: RunProfile): LaunchRequestArguments {
        val setup = prepare(project, profile)
        ByDebugSetups.getInstance(project).offer(profile, setup)
        return when (setup.backend) {
            // debugpy's adapter is *in* the debuggee: the bootstrap called `debugpy.listen()` and
            // the IDE is the one connecting. `connect` is how its adapter spells that
            ByDebugBackend.DEBUGPY -> LaunchRequestArguments(
                adapterId = ByDebugAdapter,
                request = DapStartRequest.Attach,
                arguments = mapOf("connect" to mapOf("host" to "127.0.0.1", "port" to setup.port)),
            )

            // bpd is a debug adapter that starts programs, so this is a real launch. What it
            // launches is not named here: `by run` has not chosen it yet — the module, what follows
            // it, and the directory the runner is in are all decided inside `by run`. The wrapper
            // records them, and the descriptor completes these arguments from that record before
            // the request is sent (ByDebugAdapterDescriptor.startArguments).
            //
            // Every key here is one `bpd_dap::Configuration` reads
            ByDebugBackend.BPD -> LaunchRequestArguments(
                adapterId = ByDebugAdapter,
                request = DapStartRequest.Launch,
                arguments = mapOf(
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
     *
     * The `by` a `bpd` is looked for beside is the one the run will start — resolved from the run
     * configuration's own environment choice and the configured path, exactly as
     * [dev.basedpython.pycharm.run.byCommandLine] resolves it for the run. Resolving it any other way found
     * a `bpd` beside a `by` this run was never going to use.
     */
    @Throws(ExecutionException::class)
    private fun prepare(project: Project, profile: RunProfile): ByDebugSetup {
        val settings = BasedPythonSettings.getInstance(project)
        return when (settings.debugBackend) {
            ByDebugBackend.DEBUGPY -> ByDebugSetup.create()
            ByDebugBackend.BPD -> {
                val launch = BasedPythonBinaries.launchBy(project, kind = environmentKindOf(profile))
                val python = interpreterOf(project, launch)
                val bpd = ByBpdExecutable.resolve(launch, python)
                    ?: throw ExecutionException(BasedPythonBundle.message("debug.bpd.error.notFound"))
                ByDebugSetup.forBpd(bpd, python.toString())
            }
        }
    }

    private fun environmentKindOf(profile: RunProfile): ByEnvironmentKind =
        (profile as? ByConfiguration)?.getOptions()?.environmentKind ?: ByEnvironmentKind.AUTO

    /**
     * The interpreter the debuggee runs on: the one in the environment the run's `by` comes from.
     *
     * The wrapper passes `by run`'s version probe through to this, and bpd starts the program with
     * it, so getting it wrong is not cosmetic — bpd needs PEP 669 and refuses anything below 3.13.
     *
     * The launch's own environment when it has one. A launch that names none — uv's, which
     * establishes the environment inside `uv run`, or a `by` from the download directory or `PATH`
     * — takes the interpreter [ByEnvironments.resolvePython] finds for the project, which is the
     * project's environment by the same search the toolchain's resolution makes. `python3` only
     * when there is no interpreter at all, which is `by run`'s own last resort.
     *
     * This is still the IDE naming an interpreter on `by run`'s behalf, because `--python` — the
     * only way to put the wrapper in front of the program — also switches off `by run`'s discovery.
     */
    private fun interpreterOf(project: Project, launch: ByLaunch?): Path {
        launch?.venvRoot
            ?.let { ByEnvironments.venvBinary(it, PYTHON_BINARY) }
            ?.takeIf { Files.isExecutable(it) }
            ?.let { return it }
        return ByEnvironments.resolvePython(project)?.exe ?: Path.of(DEFAULT_PYTHON)
    }

    private companion object {
        /** `by run`'s own last resort, for a project with no interpreter to name. */
        private const val DEFAULT_PYTHON = "python3"

        /** The interpreter's name inside a venv's `bin` / `Scripts`. */
        private const val PYTHON_BINARY = "python"
    }
}
