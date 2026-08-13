package dev.basedpython.pycharm.settings

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByPushKey
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies persistence semantics of [BasedPythonSettings], focusing on the
 * [BasedPythonSettings.indexGeneratedPython] Python-interop toggle and that
 * loadState round-trips the full bean.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonSettingsTest {

    private val fixture by codeInsightFixture()

    private val settings get() = BasedPythonSettings.getInstance(fixture.project)

    @Test
    fun `index generated python defaults to false`() {
        assertFalse(BasedPythonSettings.State().indexGeneratedPython)
    }

    @Test
    fun `index generated python is mutable`() {
        settings.indexGeneratedPython = true
        assertTrue(settings.indexGeneratedPython)
        settings.indexGeneratedPython = false
        assertFalse(settings.indexGeneratedPython)
    }

    @Test
    fun `getState reflects setter`() {
        settings.indexGeneratedPython = true
        assertTrue(settings.state.indexGeneratedPython)
    }

    @Test
    fun `loadState round-trips index flag`() {
        val incoming = BasedPythonSettings.State(indexGeneratedPython = true)
        settings.loadState(incoming)
        assertTrue(settings.indexGeneratedPython)
    }

    @Test
    fun `loadState copies all fields`() {
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
            inlayParameterMode = "push",
            inlayTypeMode = "never",
            inlayReturnMode = "always",
            inlayPushKey = "alt",
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
        assertEquals(ByHintMode.ON_PUSH, settings.inlayParameterMode)
        assertEquals(ByHintMode.NEVER, settings.inlayTypeMode)
        assertEquals(ByHintMode.ALWAYS, settings.inlayReturnMode)
        assertEquals(ByPushKey.ALT, settings.inlayPushKey)
        assertEquals("verbose", settings.lspTraceLevel)
        assertTrue(settings.indexGeneratedPython)
    }

    // ---- Inlay hint modes (push-to-hint) ----

    @Test
    fun `hint modes default to always, which is what the old booleans said`() {
        assertEquals(ByHintMode.ALWAYS, settings.inlayParameterMode)
        assertEquals(ByHintMode.ALWAYS, settings.inlayTypeMode)
        assertEquals(ByHintMode.ALWAYS, settings.inlayReturnMode)
        assertEquals(ByPushKey.CTRL_ALT, settings.inlayPushKey)
    }

    @Test
    fun `a settings file with only the old booleans reads as never or always`() {
        settings.loadState(
            BasedPythonSettings.State(
                inlayParameterHints = true,
                inlayTypeHints = false,
                inlayReturnHints = false,
            ),
        )
        assertEquals(ByHintMode.ALWAYS, settings.inlayParameterMode)
        assertEquals(ByHintMode.NEVER, settings.inlayTypeMode)
        assertEquals(ByHintMode.NEVER, settings.inlayReturnMode)
    }

    @Test
    fun `writing a mode writes the old boolean too, so an older plugin still reads it`() {
        settings.inlayTypeMode = ByHintMode.ON_PUSH
        assertEquals("push", settings.state.inlayTypeMode)
        assertTrue(settings.inlayTypeHints)

        settings.inlayTypeMode = ByHintMode.NEVER
        assertEquals("never", settings.state.inlayTypeMode)
        assertFalse(settings.inlayTypeHints)
    }

    @Test
    fun `a written mode wins over the boolean beside it`() {
        settings.loadState(
            BasedPythonSettings.State(
                inlayTypeHints = true,
                inlayTypeMode = "push",
                inlayPushKey = "ctrl",
            ),
        )
        assertEquals(ByHintMode.ON_PUSH, settings.inlayTypeMode)
        assertEquals(ByPushKey.CTRL, settings.inlayPushKey)
    }

    // ---- §142 per-server capability toggles ----

    @Test
    fun `capability toggles default to true`() {
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

    @Test
    fun `capability toggles are mutable`() {
        settings.byCompletion = false
        settings.buffFormatting = false
        assertFalse(settings.byCompletion)
        assertFalse(settings.buffFormatting)
        assertTrue(settings.byRename)
    }

    @Test
    fun `loadState round-trips capability toggles`() {
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

    @AfterEach
    fun resetSettings() {
        // Reset to defaults so we don't leak state between fixtures.
        settings.loadState(BasedPythonSettings.State())
    }
}
