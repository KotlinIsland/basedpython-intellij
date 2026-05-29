package dev.basedpython.pycharm.settings.app

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Verifies persistence semantics of the application-level
 * [BasedPythonAppSettings] (load/get/set + loadState round-trip) and that the
 * [BasedPythonDefaults] convenience overloads consult the live app service.
 *
 * JUnit3 style (methods begin with `test`) because we need the application
 * service registered by the platform test fixture.
 */
class BasedPythonAppSettingsTest : BasePlatformTestCase() {

    private val settings get() = BasedPythonAppSettings.getInstance()

    // --- defaults -----------------------------------------------------------

    fun `test default state path defaults are null`() {
        val s = BasedPythonAppSettings.State()
        assertNull(s.defaultByPath)
        assertNull(s.defaultBuffPath)
    }

    fun `test default state toggles enabled`() {
        val s = BasedPythonAppSettings.State()
        assertTrue(s.defaultByEnabled)
        assertTrue(s.defaultBuffEnabled)
    }

    fun `test default state scalar defaults`() {
        val s = BasedPythonAppSettings.State()
        assertEquals("", s.defaultByExtraArgs)
        assertEquals("", s.defaultBuffExtraArgs)
        assertEquals("3.10", s.defaultPythonVersion)
        assertEquals("off", s.defaultLspTraceLevel)
    }

    // --- service registration ----------------------------------------------

    fun `test getInstance returns app service`() {
        assertNotNull(settings)
        assertSame(settings, BasedPythonAppSettings.getInstance())
    }

    // --- get and set --------------------------------------------------------

    fun `test setters mutate state`() {
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

    fun `test getState reflects setter`() {
        settings.defaultByPath = "/x/by"
        assertEquals("/x/by", settings.state.defaultByPath)
    }

    // --- loadState round-trip ----------------------------------------------

    fun `test loadState copies all fields`() {
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

    fun `test loadState then getState round-trips bean`() {
        val incoming = BasedPythonAppSettings.State(
            defaultByPath = "/r/by",
            defaultPythonVersion = "3.11",
        )
        settings.loadState(incoming)
        val out = settings.state
        assertEquals(incoming.defaultByPath, out.defaultByPath)
        assertEquals(incoming.defaultPythonVersion, out.defaultPythonVersion)
    }

    fun `test copyBean produces equal independent state`() {
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

    fun `test convenience byPath uses app default when project unset`() {
        settings.defaultByPath = "/app/by"
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath(null))
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath(""))
        assertEquals("/proj/by", BasedPythonDefaults.effectiveByPath("/proj/by"))
    }

    fun `test convenience buffPath uses app default when project unset`() {
        settings.defaultBuffPath = "/app/buff"
        assertEquals("/app/buff", BasedPythonDefaults.effectiveBuffPath(null))
        assertEquals("/proj/buff", BasedPythonDefaults.effectiveBuffPath("/proj/buff"))
    }

    fun `test convenience extra args use app defaults`() {
        settings.defaultByExtraArgs = "--gby"
        settings.defaultBuffExtraArgs = "--gbuff"
        assertEquals("--gby", BasedPythonDefaults.effectiveByExtraArgs(null))
        assertEquals("--gbuff", BasedPythonDefaults.effectiveBuffExtraArgs(""))
        assertEquals("--p", BasedPythonDefaults.effectiveByExtraArgs("--p"))
    }

    fun `test convenience python version uses app default`() {
        settings.defaultPythonVersion = "3.12"
        assertEquals("3.12", BasedPythonDefaults.effectivePythonVersion(null))
        assertEquals("3.13", BasedPythonDefaults.effectivePythonVersion("3.13"))
    }

    fun `test convenience lsp trace uses app default`() {
        settings.defaultLspTraceLevel = "messages"
        assertEquals("messages", BasedPythonDefaults.effectiveLspTraceLevel(""))
        assertEquals("verbose", BasedPythonDefaults.effectiveLspTraceLevel("verbose"))
    }

    override fun tearDown() {
        try {
            // Reset to defaults so we don't leak app-level state between fixtures.
            settings.loadState(BasedPythonAppSettings.State())
        } finally {
            super.tearDown()
        }
    }
}
