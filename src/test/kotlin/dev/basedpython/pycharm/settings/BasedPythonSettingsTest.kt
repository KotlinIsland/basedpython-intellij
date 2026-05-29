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

    // ---- §142 per-server capability toggles ----

    fun `test capability toggles default to true`() {
        val d = BasedPythonSettings.State()
        assertTrue(d.byCompletion)
        assertTrue(d.byGoToDefinition)
        assertTrue(d.byFindReferences)
        assertTrue(d.byRename)
        assertTrue(d.bySemanticTokens)
        assertTrue(d.byCodeLens)
        assertTrue(d.byDocumentHighlight)
        assertTrue(d.bySignatureHelp)
        assertTrue(d.buffFormatting)
        assertTrue(d.buffCodeActions)
        assertTrue(d.buffHover)
    }

    fun `test capability toggles are mutable`() {
        settings.byCompletion = false
        settings.buffFormatting = false
        assertFalse(settings.byCompletion)
        assertFalse(settings.buffFormatting)
        assertTrue(settings.byRename)
    }

    fun `test loadState round-trips capability toggles`() {
        val incoming = BasedPythonSettings.State(
            byCompletion = false,
            byGoToDefinition = false,
            byFindReferences = false,
            byRename = false,
            bySemanticTokens = false,
            byCodeLens = false,
            byDocumentHighlight = false,
            bySignatureHelp = false,
            buffFormatting = false,
            buffCodeActions = false,
            buffHover = false,
        )
        settings.loadState(incoming)
        assertFalse(settings.byCompletion)
        assertFalse(settings.byGoToDefinition)
        assertFalse(settings.byFindReferences)
        assertFalse(settings.byRename)
        assertFalse(settings.bySemanticTokens)
        assertFalse(settings.byCodeLens)
        assertFalse(settings.byDocumentHighlight)
        assertFalse(settings.bySignatureHelp)
        assertFalse(settings.buffFormatting)
        assertFalse(settings.buffCodeActions)
        assertFalse(settings.buffHover)
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
