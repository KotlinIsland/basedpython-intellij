package dev.basedpython.pycharm.settings

import com.intellij.util.xmlb.XmlSerializer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.TEST_HINT_KINDS
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByPushKey
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies persistence semantics of [BasedPythonSettings], focusing on the
 * [BasedPythonSettings.indexGeneratedPython] Python-interop toggle and that
 * loadState round-trips the full bean.
 */
@TestFixtures
class BasedPythonSettingsTest {

    private val fixture by codeInsightFixture()

    private val settings get() = BasedPythonSettings.getInstance(fixture.project)

    @Test
    fun `index generated python defaults to false`() = onEdt {
        assertFalse(BasedPythonSettings.State().indexGeneratedPython)
    }

    @Test
    fun `recompositions are shown unless turned off, data flow only when turned on`() = onEdt {
        assertTrue(BasedPythonSettings.State().debuggerRecompositions)
        assertFalse(BasedPythonSettings.State().debuggerDataFlow)
        settings.debuggerRecompositions = false
        assertFalse(settings.debuggerRecompositions)
    }

    @Test
    fun `index generated python is mutable`() = onEdt {
        settings.indexGeneratedPython = true
        assertTrue(settings.indexGeneratedPython)
        settings.indexGeneratedPython = false
        assertFalse(settings.indexGeneratedPython)
    }

    @Test
    fun `getState reflects setter`() = onEdt {
        settings.indexGeneratedPython = true
        assertTrue(settings.state.indexGeneratedPython)
    }

    @Test
    fun `loadState round-trips index flag`() = onEdt {
        val incoming = BasedPythonSettings.State(indexGeneratedPython = true)
        settings.loadState(incoming)
        assertTrue(settings.indexGeneratedPython)
    }

    @Test
    fun `loadState copies all fields`() = onEdt {
        val incoming = BasedPythonSettings.State(
            byPath = "/tmp/by",
            buffPath = "/tmp/buff",
            byEnabled = false,
            buffEnabled = false,
            byExtraArgs = "--x",
            buffExtraArgs = "--y",
            fixAllOnSave = true,
            fixAllOnCommit = true,
            inlayParameterHints = false,
            inlayTypeHints = false,
            inlayHintModes = mutableMapOf("variableTypes" to "push", "inferredRaises" to "never"),
            inlayPushKey = "alt",
            indexGeneratedPython = true,
        )
        settings.loadState(incoming)
        assertEquals("/tmp/by", settings.byPath)
        assertEquals("/tmp/buff", settings.buffPath)
        assertFalse(settings.byEnabled)
        assertFalse(settings.buffEnabled)
        assertEquals("--x", settings.byExtraArgs)
        assertEquals("--y", settings.buffExtraArgs)
        assertTrue(settings.fixAllOnSave)
        assertTrue(settings.fixAllOnCommit)
        assertFalse(settings.inlayParameterHints)
        assertFalse(settings.inlayTypeHints)
        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind("variableTypes")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("inferredRaises")))
        assertEquals(ByPushKey.ALT, settings.inlayPushKey)
        assertTrue(settings.indexGeneratedPython)
    }

    // ---- Inlay hint modes (push-to-hint) ----

    @Test
    fun `every kind defaults to always, which is what the old toggles said`() = onEdt {
        for (kind in TEST_HINT_KINDS) {
            assertEquals(ByHintMode.ALWAYS, settings.inlayMode(kind), kind.setting)
        }
        assertEquals(ByPushKey.CTRL_ALT, settings.inlayPushKey)
    }

    @Test
    fun `a project configured before the modes keeps the hints it had`() = onEdt {
        // The old page had two toggles between them covering everything `by` sends: the
        // parameter-shaped hints, and the rest.
        settings.loadState(
            BasedPythonSettings.State(inlayParameterHints = false, inlayTypeHints = true),
        )
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("callArgumentNames")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("implicitSelf")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("implicitArguments")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("inheritedParameterDefaults")))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind("revealedTypes")))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind("variableTypes")))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind("inferredOverride")))
    }

    @Test
    fun `return-type hints set under the name the plugin used to give them keep their mode`() = onEdt {
        // by tags them, and reads its option as, returnTypes: the old key switched nothing
        settings.loadState(BasedPythonSettings.State(inlayHintModes = mutableMapOf("inferredReturnTypes" to "never", "other" to "push")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("returnTypes")))
        assertEquals(ByHintMode.NEVER, settings.inlayModes[ByHintKind("returnTypes")])
        assertEquals(mapOf("returnTypes" to false), settings.inlayModes.serverOptions().filterValues { !it })
        assertFalse(settings.inlayModes.serverOptions().containsKey("other"))
    }

    @Test
    fun `a mode is written under the name by gives the kind`() = onEdt {
        settings.setInlayMode(ByHintKind("inferredVariance"), ByHintMode.ON_PUSH)
        assertEquals("push", settings.state.inlayHintModes["inferredVariance"])
        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind("inferredVariance")))
        assertEquals(
            ByHintMode.ALWAYS,
            settings.inlayMode(ByHintKind("inferredReification")),
            "the kinds beside it are untouched",
        )
    }

    @Test
    fun `a kind this plugin has never heard of survives a round trip`() = onEdt {
        // A settings file from a newer plugin, whose extra kinds must not be dropped by this one.
        val incoming = BasedPythonSettings.State(
            inlayHintModes = mutableMapOf("somethingNewer" to "push"),
        )
        settings.loadState(incoming)
        settings.setInlayMode(ByHintKind("variableTypes"), ByHintMode.NEVER)
        assertEquals("push", settings.state.inlayHintModes["somethingNewer"])
    }

    @Test
    fun `an unreadable mode degrades to the fallback rather than failing to load`() = onEdt {
        settings.loadState(
            BasedPythonSettings.State(
                inlayTypeHints = false,
                inlayHintModes = mutableMapOf("variableTypes" to "on-hover"),
            ),
        )
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("variableTypes")))
    }

    @Test
    fun `the modes survive the settings file, not just a bean copy`() = onEdt {
        // A map is a shape the serializer has to be able to write and read back, and `loadState`
        // alone would not notice if it could not.
        settings.setInlayMode(ByHintKind("variableTypes"), ByHintMode.ON_PUSH)
        settings.setInlayMode(ByHintKind("revealedTypes"), ByHintMode.NEVER)
        settings.inlayPushKey = ByPushKey.ALT

        val written = XmlSerializer.serialize(settings.state)
        val read = XmlSerializer.deserialize(written, BasedPythonSettings.State::class.java)
        settings.loadState(BasedPythonSettings.State())
        settings.loadState(read)

        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind("variableTypes")))
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind("revealedTypes")))
        assertEquals(ByPushKey.ALT, settings.inlayPushKey)
    }

    @Test
    fun `the modes reach the server config and the collector as one value`() = onEdt {
        settings.setInlayMode(ByHintKind("revealedTypes"), ByHintMode.NEVER)
        val modes = settings.inlayModes
        assertEquals(ByHintMode.NEVER, modes[ByHintKind("revealedTypes")])
        assertEquals(false, modes.serverOptions()["revealedTypes"])
        assertTrue(modes.anyCollected)
    }

    // ---- §142 per-server capability toggles ----

    @Test
    fun `capability toggles default to true`() = onEdt {
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
    fun `capability toggles are mutable`() = onEdt {
        settings.byCompletion = false
        settings.buffFormatting = false
        assertFalse(settings.byCompletion)
        assertFalse(settings.buffFormatting)
        assertTrue(settings.byRename)
    }

    @Test
    fun `loadState round-trips capability toggles`() = onEdt {
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

    // ---- Server toggles layered over the IDE-wide defaults ----

    private val appSettings get() = BasedPythonAppSettings.getInstance()

    @Test
    fun `a project that never chose follows the IDE-wide default, whenever it changes`() = onEdt {
        appSettings.defaultByEnabled = false
        appSettings.defaultBuffEnabled = false
        assertFalse(settings.byEnabled)
        assertFalse(settings.buffEnabled)

        appSettings.defaultByEnabled = true
        assertTrue(settings.byEnabled)
        assertFalse(settings.buffEnabled)
    }

    @Test
    fun `a project's own choice outlasts a change to the default`() = onEdt {
        settings.byEnabled = true
        appSettings.defaultByEnabled = false
        assertTrue(settings.byEnabled)

        settings.buffEnabled = false
        appSettings.defaultBuffEnabled = true
        assertFalse(settings.buffEnabled)
    }

    /**
     * The settings file is where "never chose" has to survive: a project whose file held the default
     * it was created with would have stopped following the IDE-wide one the day it was created.
     */
    @Test
    fun `an unchosen toggle stays unchosen through the settings file, a chosen one stays chosen`() = onEdt {
        val untouched = XmlSerializer.deserialize(
            XmlSerializer.serialize(BasedPythonSettings.State()),
            BasedPythonSettings.State::class.java,
        )
        assertNull(untouched.byEnabled)
        assertNull(untouched.buffEnabled)

        val chosen = XmlSerializer.deserialize(
            XmlSerializer.serialize(BasedPythonSettings.State(byEnabled = false, buffEnabled = true)),
            BasedPythonSettings.State::class.java,
        )
        assertEquals(false, chosen.byEnabled)
        assertEquals(true, chosen.buffEnabled)
    }

    /** A settings file written while the toggle was a plain boolean keeps the value it recorded. */
    @Test
    fun `a settings file that recorded a toggle keeps it`() = onEdt {
        val element = org.jdom.Element("State").addContent(
            org.jdom.Element("option").setAttribute("name", "byEnabled").setAttribute("value", "false"),
        )
        appSettings.defaultByEnabled = true
        settings.loadState(XmlSerializer.deserialize(element, BasedPythonSettings.State::class.java))
        assertFalse(settings.byEnabled)
        assertTrue(settings.buffEnabled)
    }

    @AfterEach
    fun resetSettings() = onEdt {
        // Reset to defaults so we don't leak state between fixtures.
        settings.loadState(BasedPythonSettings.State())
        appSettings.loadState(BasedPythonAppSettings.State())
    }

    /** Both are off until asked for: fixes rewrite a file the user has not asked to be rewritten. */
    @Test
    fun `fixes are off on save and on commit by default`() = onEdt {
        assertFalse(settings.fixAllOnSave)
        assertFalse(settings.fixAllOnCommit)
    }

    /** Commit is configured separately from save, so one being on says nothing about the other. */
    @Test
    fun `the commit toggle is independent of save`() = onEdt {
        settings.fixAllOnSave = true
        assertFalse(settings.fixAllOnCommit)

        settings.fixAllOnCommit = true
        settings.fixAllOnSave = false
        assertTrue(settings.fixAllOnCommit)
    }
}
