package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
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
    fun `a by file gets the affordance unless the IDE already provides one`() {
        val configuration = configurationFor("main.by", "x = 1\ny = 2\n")
        if (ideHasLogpoints) {
            assertNull(configuration, "IDEA's own logpoints provider should keep the gap")
            return
        }
        assertNotNull(configuration, "expected an inter-line configuration for a .by file")
        assertTrue(configuration!!.breakpointProperties.isLogging, "the gap should add a log point")
        assertEquals("Add Log", configuration.hoverTooltip)
    }

    @Test
    fun `other languages are left alone`() {
        assertNull(configurationFor("notes.txt", "x = 1\n"))
    }

    @Test
    fun `the id is stable, since the gutter caches configurations by it`() {
        assertEquals("basedpython-logpoint", provider.uniqueId)
    }
}
