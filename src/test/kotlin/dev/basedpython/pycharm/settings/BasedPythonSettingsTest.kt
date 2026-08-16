package dev.basedpython.pycharm.settings

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
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
            fixAllOnSave = true,
            formatOnCommit = true,
            fixAllOnCommit = true,
            inlayParameterHints = false,
            inlayTypeHints = false,
            inlayHintModes = mutableMapOf("variableTypes" to "push", "inferredRaises" to "never"),
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
        assertTrue(settings.fixAllOnSave)
        assertTrue(settings.formatOnCommit)
        assertTrue(settings.fixAllOnCommit)
        assertFalse(settings.inlayParameterHints)
        assertFalse(settings.inlayTypeHints)
        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind.VARIABLE_TYPES))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.INFERRED_RAISES))
        assertEquals(ByPushKey.ALT, settings.inlayPushKey)
        assertEquals("verbose", settings.lspTraceLevel)
        assertTrue(settings.indexGeneratedPython)
    }

    // ---- Inlay hint modes (push-to-hint) ----

    @Test
    fun `every kind defaults to always, which is what the old toggles said`() {
        for (kind in ByHintKind.entries) {
            assertEquals(ByHintMode.ALWAYS, settings.inlayMode(kind), kind.name)
        }
        assertEquals(ByPushKey.CTRL_ALT, settings.inlayPushKey)
    }

    @Test
    fun `a project configured before the modes keeps the hints it had`() {
        // The old page had two toggles between them covering everything `by` sends: the
        // parameter-shaped hints, and the rest.
        settings.loadState(
            BasedPythonSettings.State(inlayParameterHints = false, inlayTypeHints = true),
        )
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.CALL_ARGUMENT_NAMES))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.IMPLICIT_SELF))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.IMPLICIT_ARGUMENTS))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind.VARIABLE_TYPES))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind.INFERRED_OVERRIDE))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind.OTHER))
    }

    @Test
    fun `a mode is written under the name by gives the kind`() {
        settings.setInlayMode(ByHintKind.INFERRED_VARIANCE, ByHintMode.ON_PUSH)
        assertEquals("push", settings.state.inlayHintModes["inferredVariance"])
        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind.INFERRED_VARIANCE))
        assertEquals(
            ByHintMode.ALWAYS,
            settings.inlayMode(ByHintKind.INFERRED_REIFICATION),
            "the kinds beside it are untouched",
        )
    }

    @Test
    fun `a kind this plugin has never heard of survives a round trip`() {
        // A settings file from a newer plugin, whose extra kinds must not be dropped by this one.
        val incoming = BasedPythonSettings.State(
            inlayHintModes = mutableMapOf("somethingNewer" to "push"),
        )
        settings.loadState(incoming)
        settings.setInlayMode(ByHintKind.VARIABLE_TYPES, ByHintMode.NEVER)
        assertEquals("push", settings.state.inlayHintModes["somethingNewer"])
    }

    @Test
    fun `an unreadable mode degrades to the fallback rather than failing to load`() {
        settings.loadState(
            BasedPythonSettings.State(
                inlayTypeHints = false,
                inlayHintModes = mutableMapOf("variableTypes" to "on-hover"),
            ),
        )
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.VARIABLE_TYPES))
    }

    @Test
    fun `the modes survive the settings file, not just a bean copy`() {
        // A map is a shape the serializer has to be able to write and read back, and `loadState`
        // alone would not notice if it could not.
        settings.setInlayMode(ByHintKind.VARIABLE_TYPES, ByHintMode.ON_PUSH)
        settings.setInlayMode(ByHintKind.REVEALED_TYPES, ByHintMode.NEVER)
        settings.inlayPushKey = ByPushKey.ALT

        val written = XmlSerializer.serialize(settings.state)
        val read = XmlSerializer.deserialize(written, BasedPythonSettings.State::class.java)
        settings.loadState(BasedPythonSettings.State())
        settings.loadState(read)

        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind.VARIABLE_TYPES))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.REVEALED_TYPES))
        assertEquals(ByPushKey.ALT, settings.inlayPushKey)
    }

    @Test
    fun `the modes reach the server config and the collector as one value`() {
        settings.setInlayMode(ByHintKind.REVEALED_TYPES, ByHintMode.NEVER)
        val modes = settings.inlayModes
        assertEquals(ByHintMode.NEVER, modes[ByHintKind.REVEALED_TYPES])
        assertEquals(false, modes.serverOptions()["revealedTypes"])
        assertTrue(modes.anyCollected)
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

    /** The two save toggles become the passes the on-save action runs, in run order. */
    @Test
    fun `save toggles become cleanup passes`() {
        settings.formatOnSave = false
        settings.fixAllOnSave = false
        assertTrue(settings.cleanupOnSave.isEmpty())

        settings.formatOnSave = true
        assertEquals(
            setOf(dev.basedpython.pycharm.format.ByCleanupOp.FormatAndOptimizeImports),
            settings.cleanupOnSave,
        )

        settings.fixAllOnSave = true
        assertEquals(
            setOf(
                dev.basedpython.pycharm.format.ByCleanupOp.FixAll,
                dev.basedpython.pycharm.format.ByCleanupOp.FormatAndOptimizeImports,
            ),
            settings.cleanupOnSave,
        )
    }

    /** Commit is configured separately from save, so one being on says nothing about the other. */
    @Test
    fun `commit toggles are independent of save`() {
        settings.formatOnSave = true
        settings.fixAllOnSave = true
        assertTrue(settings.cleanupOnCommit.isEmpty())

        settings.fixAllOnCommit = true
        assertEquals(
            setOf(dev.basedpython.pycharm.format.ByCleanupOp.FixAll),
            settings.cleanupOnCommit,
        )
    }
}
