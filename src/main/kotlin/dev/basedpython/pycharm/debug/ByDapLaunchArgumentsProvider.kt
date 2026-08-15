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
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.runSubcommandArgs
import dev.basedpython.pycharm.run.test.ByPytest
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

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
            // `by run` transpiled into — bpd inherits it from the wrapper and the debuggee inherits
            // it from bpd. That is what lets the IDE name a program it cannot know the path of.
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
                ByDebugSetup.forBpd(bpd, launch?.env?.get("PYTHON") ?: DEFAULT_PYTHON)
            }
        }
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

        /** What `by run` falls back to when `PYTHON` names nothing. */
        private const val DEFAULT_PYTHON = "python3"

        /** The binary whose directory a sibling `bpd` is looked for in. */
        private const val BY_BINARY = "by"
    }
}
