package dev.basedpython.pycharm.debug

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.platform.dap.DapStartRequest
import com.intellij.platform.dap.DebugAdapterSupportProvider
import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The platform's own view of this plugin's debugger.
 *
 * Everything else about the backends is tested by calling their classes directly, which proves the
 * classes work and nothing about whether the platform ever reaches them. An extension the platform
 * does not accept is not an error anywhere — it is logged once at load and the feature silently
 * does not exist.
 *
 * So this asks the *platform* for the providers, by the extension point they are registered on,
 * and drives them the way `DapProgramRunner` does.
 *
 * Uses this suite's own [codeInsightFixture] rather than a `projectFixture`. A run configuration
 * belongs to a project, so one is needed — but a *real* project alongside the light ones every
 * other test here builds leaves one of them undisposed, and the run fails on a leak in somebody
 * else's test. The light project answers the routing question just as well.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByDebugRoutingTest {

    private val fixture by codeInsightFixture()
    private val project: Project get() = fixture.project

    /** The provider the platform has, not the one this test could construct. */
    private fun provider(): DapLaunchArgumentsProvider =
        DapLaunchArgumentsProvider.EP_NAME.extensionList
            .firstOrNull { it is ByDapLaunchArgumentsProvider }
            ?: error(
                "the platform has no ByDapLaunchArgumentsProvider. it is registered on " +
                    "platform.dap.launchArgumentsProvider in plugin.xml, and without it " +
                    "DapProgramRunner.canRun says no and `.by` files cannot be debugged at all",
            )

    private fun configuration() = RunManager.getInstance(project)
        .createConfiguration("demo", BasedPythonRunConfigurationType().configurationFactories[0])
        .configuration

    @Test
    fun `the adapter is one the platform knows about`() {
        val adapters = DebugAdapterSupportProvider.EP_NAME.extensionList
        assertTrue(
            adapters.any { it.adapterId == ByDebugAdapter },
            "the platform has no ByDebugAdapterSupportProvider, so nothing can serve a `.by` " +
                "session however well the rest of it works",
        )
    }

    @Test
    fun `only the debug executor routes a by run configuration`() {
        val provider = provider()
        val profile = configuration()

        assertTrue(
            provider.isApplicable(DefaultDebugExecutor.EXECUTOR_ID, profile),
            "Debug on a `by run` configuration has to reach this plugin",
        )
        // load bearing rather than defensive: `DapProgramRunner.canRun` accepts the Run executor
        // too for any profile a provider claims, so saying yes here routes ordinary runs through
        // the debug adapter as well
        assertTrue(
            !provider.isApplicable(DefaultRunExecutor.EXECUTOR_ID, profile),
            "Run must not go through the debug adapter",
        )
    }

    @Test
    fun `the bpd backend asks the platform for a launch`() {
        val settings = BasedPythonSettings.getInstance(project)
        val previous = settings.debugBackend
        try {
            settings.debugBackend = ByDebugBackend.BPD
            val arguments = try {
                provider().getLaunchArguments(project, configuration())
            } catch (refused: Exception) {
                // no `bpd` on this machine is a refusal by design — and it is the *setting* being
                // read that this test is about, which a refusal naming bpd already proves
                assertTrue(
                    refused.message.orEmpty().contains("bpd"),
                    "a bpd session with no bpd should say so: ${refused.message}",
                )
                return
            }
            assertEquals(
                DapStartRequest.Launch,
                arguments.request,
                "bpd is an adapter that starts programs, so this is a real launch",
            )
            assertEquals(
                "_by_runner.py",
                arguments.arguments["program"],
                "the program is the runner shim, relative to the directory `by run` transpiled into",
            )
        } finally {
            settings.debugBackend = previous
        }
    }

    @Test
    fun `the debugpy backend asks the platform to attach`() {
        val settings = BasedPythonSettings.getInstance(project)
        val previous = settings.debugBackend
        try {
            settings.debugBackend = ByDebugBackend.DEBUGPY
            val arguments = provider().getLaunchArguments(project, configuration())
            // debugpy's adapter lives *inside* the debuggee: the bootstrap called
            // `debugpy.listen()` and the IDE is the one dialling in
            assertEquals(DapStartRequest.Attach, arguments.request)
            assertNotNull(arguments.arguments["connect"], "an attach names where to connect")
        } finally {
            settings.debugBackend = previous
        }
    }
}
