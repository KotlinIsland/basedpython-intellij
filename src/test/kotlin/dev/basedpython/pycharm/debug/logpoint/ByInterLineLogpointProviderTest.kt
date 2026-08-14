package dev.basedpython.pycharm.debug.logpoint

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValueSource
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which editors get the gutter-gap "Add Log" affordance.
 *
 * The interesting case is the one this suite cannot choose: in IntelliJ IDEA the Java plugin
 * registers its own provider, the better one, and only the first configuration found is used — so
 * this provider has to stand aside there and speak up everywhere else. Which branch runs depends on
 * the IDE the tests are built against, so both are asserted, and the deferral is asserted against
 * the same extension point the provider consults rather than a hardcoded expectation.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByInterLineLogpointProviderTest {

    private val fixture by codeInsightFixture()

    private val provider = ByInterLineLogpointProvider()

    private fun configurationFor(name: String, text: String) = runBlocking {
        fixture.configureByText(name, text)
        provider.getConfiguration(fixture.editor).firstOrNull()
    }

    private val ideHasLogpoints: Boolean
        get() = ApplicationManager.getApplication().extensionArea
            .hasExtensionPoint("com.intellij.xdebugger.logpoints.editorsProviderFactory")

    @Test
    fun `a by file gets the affordance in every IDE, including one with logpoints of its own`() {
        UISettings.getInstance().showBreakpointsOverLineNumbers = true
        val configuration = configurationFor("main.by", "x = 1\ny = 2\n")
        assertNotNull(configuration, "expected an inter-line configuration for a .by file")
        assertTrue(configuration!!.breakpointProperties.isLogging, "the gap should add a log point")
        assertEquals("Add Log", configuration.hoverTooltip)
        assertNotNull(
            configuration.animator,
            "without an animator the gutter opens no gap and paints nothing, however good the rest is",
        )
    }

    /** The gap does not defer, but the field that opens after it does — two prompts would be two fields. */
    @Test
    fun `the inline prompt defers to the IDE's own where there is one`() {
        assertEquals(!ideHasLogpoints, ByLogpoints.pluginOwnsLogpointPrompt())
    }

    @Test
    fun `setting the preference to ide gives the gap up entirely`() {
        withProvider("ide") {
            UISettings.getInstance().showBreakpointsOverLineNumbers = true
            assertNull(configurationFor("main.by", "x = 1\ny = 2\n"))
        }
    }

    /**
     * The path a developer on IntelliJ IDEA has to take to see this implementation at all, since by
     * default it gives way there — and, for anyone else, the path an IDE the detection misreads
     * would need.
     */
    @Test
    fun `forcing the plugin's implementation offers the affordance even where the IDE has its own`() {
        withProvider("plugin") {
            UISettings.getInstance().showBreakpointsOverLineNumbers = true
            val configuration = configurationFor("main.by", "x = 1\ny = 2\n")
            assertNotNull(configuration, "expected the gap affordance once the plugin is forced to draw it")
            assertTrue(configuration!!.breakpointProperties.isLogging)
        }
    }

    @Test
    fun `the gap needs breakpoints over the line numbers to be a place you can click`() {
        withProvider("plugin") {
            UISettings.getInstance().showBreakpointsOverLineNumbers = false
            assertNull(
                configurationFor("main.by", "x = 1\ny = 2\n"),
                "with breakpoints beside the numbers there is no gutter row between two lines",
            )
        }
    }

    @Test
    fun `other languages are left alone`() {
        withProvider("plugin") {
            UISettings.getInstance().showBreakpointsOverLineNumbers = true
            assertNull(configurationFor("notes.txt", "x = 1\n"))
        }
    }

    /** Runs [body] with the provider preference forced, restoring both it and the UI setting after. */
    private fun withProvider(preference: String, body: () -> Unit) {
        val key = Registry.get("basedpython.logpoints.provider")
        val settings = UISettings.getInstance()
        val previousBreakpoints = settings.showBreakpointsOverLineNumbers
        key.setSelectedOption(preference, RegistryValueSource.USER)
        try {
            body()
        } finally {
            key.resetToDefault()
            settings.showBreakpointsOverLineNumbers = previousBreakpoints
        }
    }

    @Test
    fun `the id is stable, since the gutter caches configurations by it`() {
        assertEquals("basedpython-logpoint", provider.uniqueId)
    }
}
