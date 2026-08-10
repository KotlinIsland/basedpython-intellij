package dev.basedpython.pycharm.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.LaunchRequestArguments
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration

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
        val setup = ByDebugSetup.create()
        (profile as? UserDataHolder)?.putUserData(ByDebugSetup.KEY, setup)
        return LaunchRequestArguments(
            adapterId = ByDebugAdapter,
            request = DapStartRequest.Attach,
            // `connect` is how debugpy's adapter spells "the debuggee spawned me via
            // debugpy.listen()", which is exactly what the bootstrap does.
            arguments = mapOf("connect" to mapOf("host" to "127.0.0.1", "port" to setup.port)),
        )
    }
}
