package dev.basedpython.pycharm.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies persistence semantics of [BasedPythonSettings], focusing on the
 * [BasedPythonSettings.indexGeneratedPython] Python-interop toggle and that
 * loadState round-trips the full bean.
 */
class BasedPythonSettingsTest : BasePlatformTestCase() {

    private val settings get() = BasedPythonSettings.getInstance(project)

    fun `test index generated python defaults to false`() {
        assertFalse(BasedPythonSettings.State().indexGeneratedPython)
    }

    fun `test index generated python is mutable`() {
        settings.indexGeneratedPython = true
        assertTrue(settings.indexGeneratedPython)
        settings.indexGeneratedPython = false
        assertFalse(settings.indexGeneratedPython)
    }

    fun `test getState reflects setter`() {
        settings.indexGeneratedPython = true
        assertTrue(settings.state.indexGeneratedPython)
    }

    fun `test loadState round-trips index flag`() {
        val incoming = BasedPythonSettings.State(indexGeneratedPython = true)
        settings.loadState(incoming)
        assertTrue(settings.indexGeneratedPython)
    }

    fun `test loadState copies all fields`() {
        val incoming = BasedPythonSettings.State(
            byPath = "/tmp/by",
            buffPath = "/tmp/buff",
            byEnabled = false,
            buffEnabled = false,
            byExtraArgs = "--x",
            buffExtraArgs = "--y",
            pythonVersion = "3.13",
            formatOnSave = true,
            inlayParameterHints = false,
            inlayTypeHints = false,
            inlayReturnHints = false,
            lspTraceLevel = "verbose",
            indexGeneratedPython = true,
        )
        settings.loadState(incoming)
        assertEquals("/tmp/by", settings.byPath)
        assertEquals("/tmp/buff", settings.buffPath)
        assertFalse(settings.byEnabled)
        assertFalse(settings.buffEnabled)
        assertEquals("--x", settings.byExtraArgs)
        assertEquals("--y", settings.buffExtraArgs)
        assertEquals("3.13", settings.pythonVersion)
        assertTrue(settings.formatOnSave)
        assertFalse(settings.inlayParameterHints)
        assertFalse(settings.inlayTypeHints)
        assertFalse(settings.inlayReturnHints)
        assertEquals("verbose", settings.lspTraceLevel)
        assertTrue(settings.indexGeneratedPython)
    }

    override fun tearDown() {
        try {
            // Reset to defaults so we don't leak state between fixtures.
            settings.loadState(BasedPythonSettings.State())
        } finally {
            super.tearDown()
        }
    }
}
