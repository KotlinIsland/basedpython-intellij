package dev.basedpython.pycharm.settings.app

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies persistence semantics of the application-level
 * [BasedPythonAppSettings] (load/get/set + loadState round-trip) and that the
 * [BasedPythonDefaults] convenience overloads consult the live app service.
 *
 * The settings are application-level, but the fixture is still what stands the test application up,
 * so it is declared here the same way the project-scoped tests declare it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonAppSettingsTest {

    @Suppress("unused")
    private val fixture by codeInsightFixture()

    private val settings get() = BasedPythonAppSettings.getInstance()

    // --- defaults -----------------------------------------------------------

    @Test
    fun `default state path defaults are null`() {
        val s = BasedPythonAppSettings.State()
        assertNull(s.defaultByPath)
        assertNull(s.defaultBuffPath)
    }

    @Test
    fun `default state toggles enabled`() {
        val s = BasedPythonAppSettings.State()
        assertTrue(s.defaultByEnabled)
        assertTrue(s.defaultBuffEnabled)
    }

    @Test
    fun `default state scalar defaults`() {
        val s = BasedPythonAppSettings.State()
        assertEquals("", s.defaultByExtraArgs)
        assertEquals("", s.defaultBuffExtraArgs)
        assertEquals("3.10", s.defaultPythonVersion)
        assertEquals("off", s.defaultLspTraceLevel)
    }

    // --- service registration ----------------------------------------------

    @Test
    fun `getInstance returns app service`() {
        assertNotNull(settings)
        assertSame(settings, BasedPythonAppSettings.getInstance())
    }

    // --- get and set --------------------------------------------------------

    @Test
    fun `setters mutate state`() {
        settings.defaultByPath = "/opt/by"
        settings.defaultBuffPath = "/opt/buff"
        settings.defaultByEnabled = false
        settings.defaultBuffEnabled = false
        settings.defaultByExtraArgs = "--by"
        settings.defaultBuffExtraArgs = "--buff"
        settings.defaultPythonVersion = "3.12"
        settings.defaultLspTraceLevel = "verbose"

        assertEquals("/opt/by", settings.defaultByPath)
        assertEquals("/opt/buff", settings.defaultBuffPath)
        assertFalse(settings.defaultByEnabled)
        assertFalse(settings.defaultBuffEnabled)
        assertEquals("--by", settings.defaultByExtraArgs)
        assertEquals("--buff", settings.defaultBuffExtraArgs)
        assertEquals("3.12", settings.defaultPythonVersion)
        assertEquals("verbose", settings.defaultLspTraceLevel)
    }

    @Test
    fun `getState reflects setter`() {
        settings.defaultByPath = "/x/by"
        assertEquals("/x/by", settings.state.defaultByPath)
    }

    // --- loadState round-trip ----------------------------------------------

    @Test
    fun `loadState copies all fields`() {
        val incoming = BasedPythonAppSettings.State(
            defaultByPath = "/g/by",
            defaultBuffPath = "/g/buff",
            defaultByEnabled = false,
            defaultBuffEnabled = false,
            defaultByExtraArgs = "--a",
            defaultBuffExtraArgs = "--b",
            defaultPythonVersion = "3.13",
            defaultLspTraceLevel = "messages",
        )
        settings.loadState(incoming)
        assertEquals("/g/by", settings.defaultByPath)
        assertEquals("/g/buff", settings.defaultBuffPath)
        assertFalse(settings.defaultByEnabled)
        assertFalse(settings.defaultBuffEnabled)
        assertEquals("--a", settings.defaultByExtraArgs)
        assertEquals("--b", settings.defaultBuffExtraArgs)
        assertEquals("3.13", settings.defaultPythonVersion)
        assertEquals("messages", settings.defaultLspTraceLevel)
    }

    @Test
    fun `loadState then getState round-trips bean`() {
        val incoming = BasedPythonAppSettings.State(
            defaultByPath = "/r/by",
            defaultPythonVersion = "3.11",
        )
        settings.loadState(incoming)
        val out = settings.state
        assertEquals(incoming.defaultByPath, out.defaultByPath)
        assertEquals(incoming.defaultPythonVersion, out.defaultPythonVersion)
    }

    @Test
    fun `copyBean produces equal independent state`() {
        val src = BasedPythonAppSettings.State(
            defaultByPath = "/c/by",
            defaultBuffExtraArgs = "--copy",
            defaultLspTraceLevel = "verbose",
        )
        val dst = BasedPythonAppSettings.State()
        XmlSerializerUtil.copyBean(src, dst)
        assertEquals(src, dst)
        // Mutating source after copy must not affect destination.
        src.defaultByPath = "/c/changed"
        assertEquals("/c/by", dst.defaultByPath)
    }

    // --- resolution helper through live service -----------------------------

    @Test
    fun `convenience byPath uses app default when project unset`() {
        settings.defaultByPath = "/app/by"
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath(null))
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath(""))
        assertEquals("/proj/by", BasedPythonDefaults.effectiveByPath("/proj/by"))
    }

    @Test
    fun `convenience buffPath uses app default when project unset`() {
        settings.defaultBuffPath = "/app/buff"
        assertEquals("/app/buff", BasedPythonDefaults.effectiveBuffPath(null))
        assertEquals("/proj/buff", BasedPythonDefaults.effectiveBuffPath("/proj/buff"))
    }

    @Test
    fun `convenience extra args use app defaults`() {
        settings.defaultByExtraArgs = "--gby"
        settings.defaultBuffExtraArgs = "--gbuff"
        assertEquals("--gby", BasedPythonDefaults.effectiveByExtraArgs(null))
        assertEquals("--gbuff", BasedPythonDefaults.effectiveBuffExtraArgs(""))
        assertEquals("--p", BasedPythonDefaults.effectiveByExtraArgs("--p"))
    }

    @Test
    fun `convenience python version uses app default`() {
        settings.defaultPythonVersion = "3.12"
        assertEquals("3.12", BasedPythonDefaults.effectivePythonVersion(null))
        assertEquals("3.13", BasedPythonDefaults.effectivePythonVersion("3.13"))
    }

    @Test
    fun `convenience lsp trace uses app default`() {
        settings.defaultLspTraceLevel = "messages"
        assertEquals("messages", BasedPythonDefaults.effectiveLspTraceLevel(""))
        assertEquals("verbose", BasedPythonDefaults.effectiveLspTraceLevel("verbose"))
    }

    @AfterEach
    fun resetSettings() {
        // Reset to defaults so we don't leak app-level state between tests.
        settings.loadState(BasedPythonAppSettings.State())
    }
}
