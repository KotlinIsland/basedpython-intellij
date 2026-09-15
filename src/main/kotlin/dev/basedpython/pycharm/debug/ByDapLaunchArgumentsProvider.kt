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
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByConfiguration
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.settings.BasedPythonSettings

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
            // launches is not named here: `by run` has not chosen it yet — the interpreter, the
            // module, what follows it, and the directory the runner is in are all decided inside
            // `by run`. The wrapper records them, and the descriptor completes these arguments from
            // that record before the request is sent (ByDebugAdapterDescriptor.startArguments).
            //
            // Every key here is one `bpd_dap::Configuration` reads
            ByDebugBackend.BPD -> LaunchRequestArguments(
                adapterId = ByDebugAdapter,
                request = DapStartRequest.Launch,
                arguments = mapOf(
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
     * The `by` a `bpd` is looked for beside is the one the run will start — resolved from the run
     * configuration's own environment choice and the configured path, exactly as
     * [dev.basedpython.pycharm.run.byCommandLine] resolves it for the run. Resolving it any other way found
     * a `bpd` beside a `by` this run was never going to use.
     *
     * A session with no `bpd` found here is not refused here: the wrapper also looks beside the
     * interpreter `by run` chooses, which nothing can know before `by run` has chosen it. When that
     * finds none either, the wrapper exits before the program starts and the connection says so.
     * Falling back to debugpy silently would be worse: the user chose a debugger and would get a
     * different one.
     */
    @Throws(ExecutionException::class)
    private fun prepare(project: Project, profile: RunProfile): ByDebugSetup {
        val settings = BasedPythonSettings.getInstance(project)
        return when (settings.debugBackend) {
            ByDebugBackend.DEBUGPY -> ByDebugSetup.create()
            ByDebugBackend.BPD -> ByDebugSetup.forBpd(
                ByBpdExecutable.find(BasedPythonBinaries.launchBy(project, kind = environmentKindOf(profile))),
            )
        }
    }

    private fun environmentKindOf(profile: RunProfile): ByEnvironmentKind =
        (profile as? ByConfiguration)?.getOptions()?.environmentKind ?: ByEnvironmentKind.AUTO

}
