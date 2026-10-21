package dev.basedpython.pycharm.debug

import com.intellij.execution.RunManager
import com.intellij.platform.dap.DapLaunchArgumentsProvider
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the debugger settings page puts in bpd's `launch`: keys `bpd_dap::Configuration` reads,
 * spelled as it spells them — bpd refuses a misspelled bound rather than ignoring it — and a bound
 * left empty left out, so that it is bpd's own default.
 */
@TestFixtures
class ByDebuggerSettingsTest {

    private val fixture by codeInsightFixture()

    private fun withSettings(change: ByDebuggerSettings.State.() -> Unit, body: (ByDebuggerSettings) -> Unit) {
        val settings = ByDebuggerSettings.getInstance()
        val saved = ByDebuggerSettings.State().also { XmlSerializerUtil.copyBean(settings.state, it) }
        try {
            settings.loadState(ByDebuggerSettings.State().apply(change))
            body(settings)
        } finally {
            settings.loadState(saved)
        }
    }

    @Test
    fun `the page is one the platform knows about, in General and in Data Views`() = onEdt {
        // found among the platform's `xdebugger.settings`, or this throws
        val settings = ByDebuggerSettings.getInstance()
        assertEquals(1, settings.createConfigurables(DebuggerSettingsCategory.GENERAL).size)
        assertEquals(1, settings.createConfigurables(DebuggerSettingsCategory.DATA_VIEWS).size)
        assertTrue(settings.createConfigurables(DebuggerSettingsCategory.STEPPING).isEmpty(), "bpd has no step filters")
    }

    @Test
    fun `untouched, only bpd's own defaults for the two switches are said and no bound is`() = onEdt {
        withSettings({}) { settings ->
            assertEquals(
                mapOf("stopTheWorld" to false, "variables" to mapOf("attributes" to true, "repr" to false)),
                settings.launchArguments(),
            )
        }
    }

    @Test
    fun `every bound set is sent under bpd's name for it`() = onEdt {
        withSettings({
            stopTheWorld = true
            depth = 1
            children = 5
            text = 12
            budget = 64
            attributes = false
            repr = true
        }) { settings ->
            assertEquals(
                mapOf(
                    "stopTheWorld" to true,
                    "variables" to mapOf(
                        "depth" to 1, "children" to 5, "text" to 12, "budget" to 64,
                        "attributes" to false, "repr" to true,
                    ),
                ),
                settings.launchArguments(),
            )
        }
    }

    @Test
    fun `a bpd session's launch carries them`() = onEdt {
        val project = fixture.project
        val backend = BasedPythonSettings.getInstance(project)
        val previous = backend.debugBackend
        try {
            backend.debugBackend = ByDebugBackend.BPD
            withSettings({ depth = 1 }) { _ ->
                val provider = DapLaunchArgumentsProvider.EP_NAME.extensionList.first { it is ByDapLaunchArgumentsProvider }
                val configuration = RunManager.getInstance(project)
                    .createConfiguration("demo", BasedPythonRunConfigurationType().configurationFactories[0])
                    .configuration
                val arguments = provider.getLaunchArguments(project, configuration).arguments
                assertEquals(false, arguments["stopOnEntry"])
                assertEquals(false, arguments["stopTheWorld"])
                assertEquals(1, (arguments["variables"] as Map<*, *>)["depth"])
            }
        } finally {
            backend.debugBackend = previous
        }
    }
}
