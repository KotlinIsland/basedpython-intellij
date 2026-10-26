package dev.basedpython.pycharm.lsp.inject

import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.general.LanguageInjectionContributor
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.lang.injection.general.SimpleInjection
import org.intellij.plugins.intelliLang.inject.TemporaryPlacesRegistry
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An injection the user asked for — what *Inject language or reference* registers — placed by
 * [ByLanguageInjectionPerformer] on the text `by` says a string holds.
 *
 * The request for it is stood in for by a contributor that names plain text for every `.by`
 * string, which is the shape IntelliLang's temporary injections reach the platform in; the
 * platform's own adapter then hands it to the performers registered for the language, exactly as it
 * does in the IDE. The outline is put in with [OutlineSpec], as `by` would answer it.
 */
@TestFixtures
class ByLanguageInjectionPerformerTest {

    private val fixture by codeInsightFixture()

    private val manager get() = InjectedLanguageManager.getInstance(fixture.project)

    private fun everyStringIsPlainText(supportId: String = TemporaryPlacesRegistry.SUPPORT_ID) {
        val contributor = LanguageInjectionContributor { context ->
            SimpleInjection(PlainTextLanguage.INSTANCE, "", "", supportId).takeIf { context is BasedPythonStringLiteral }
        }
        LanguageInjectionContributor.INJECTOR_EXTENSION.addExplicitExtension(
            BasedPythonLanguage, contributor, fixture.testRootDisposable,
        )
    }

    private fun injectedAt(file: PsiFile, offset: Int): PsiFile? =
        manager.findInjectedElementAt(file, offset)?.containingFile

    @Test
    fun `the performer is the one the platform asks for a basedpython string`() {
        val performers = LanguageInjectionPerformer.INJECTOR_EXTENSION.allForLanguage(BasedPythonLanguage)
        assertTrue(performers.any { it is ByLanguageInjectionPerformer }, "$performers")
    }

    @Test
    fun `a dedented string is injected as the text the program holds, a run per line`() = onEdt {
        everyStringIsPlainText()
        val q = "\"\"\""
        val source = "def render():\n    page = $q\n        <div>\n\n          <p>hi</p>\n        </div>\n        $q\n"
        val file = fixture.configureByText("main.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string(
                "$q\n        <div>\n\n          <p>hi</p>\n        </div>\n        $q",
                strippedIndent = 8,
                text = listOf("<div>\n", "\n", "  <p>hi</p>\n", "</div>"),
            )
        }

        val injected = injectedAt(file, source.indexOf("<div>") + 1)

        // Without the performer the platform injects everything between the quotes: the opening
        // line break, eight spaces in front of every line, and the closing quotes' indentation.
        assertEquals("<div>\n\n  <p>hi</p>\n</div>", injected?.let { manager.getUnescapedText(it) })
        val window = injected!!.viewProvider.document as DocumentWindow
        assertEquals(4, window.hostRanges.size)
        assertEquals(source.indexOf("<div>"), window.hostRanges.first().startOffset)
    }

    @Test
    fun `IntelliLang's reading of a language= comment is by's, so it is taken and nothing is injected`() = onEdt {
        // the shape IntelliLang's comment injector hands over: an injection of its "comment" support
        everyStringIsPlainText(supportId = "comment")
        val source = "loose = \"x(y\"  # language=RegExp\nother = \"x(y\"\n"
        val file = fixture.configureByText("main.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("\"x(y\"", text = listOf("x(y"))
            string("\"x(y\"", text = listOf("x(y"))
        }

        // without the performer taking it, the platform's own placement injects the unmarked `other`
        assertNull(injectedAt(file, source.lastIndexOf("x(y") + 1))
    }

    @Test
    fun `without an outline for the text the performer declines and leaves the platform's placement`() = onEdt {
        val file = fixture.configureByText("main.by", "a = \"x\"\n")
        val host = PsiTreeUtil.findChildOfType(file, BasedPythonStringLiteral::class.java)!!
        val registrar = RecordingRegistrar()

        val handled = ByLanguageInjectionPerformer().performInjection(
            registrar, SimpleInjection(PlainTextLanguage.INSTANCE, "", "", TemporaryPlacesRegistry.SUPPORT_ID), host,
        )

        assertEquals(false, handled)
        assertEquals(emptyList<TextRange>(), registrar.places)
    }

    @Test
    fun `a prefix and suffix go on the first and last runs`() = onEdt {
        val q = "\"\"\""
        val source = "page = $q\n    a\n    b\n    $q\n"
        val file = fixture.configureByText("main.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("$q\n    a\n    b\n    $q", strippedIndent = 4, text = listOf("a\n", "b"))
        }
        val host = PsiTreeUtil.findChildOfType(file, BasedPythonStringLiteral::class.java)!!
        val registrar = RecordingRegistrar()

        ByLanguageInjectionPerformer().performInjection(
            registrar, SimpleInjection(PlainTextLanguage.INSTANCE, "<", ">", TemporaryPlacesRegistry.SUPPORT_ID), host,
        )

        assertEquals(listOf("<" to null, null to ">"), registrar.affixes)
        assertEquals(listOf("a\n", "b"), registrar.places.map { it.substring(host.text) })
    }

    private class RecordingRegistrar : com.intellij.lang.injection.MultiHostRegistrar {
        val places = mutableListOf<TextRange>()
        val affixes = mutableListOf<Pair<String?, String?>>()

        override fun startInjecting(language: com.intellij.lang.Language) = this
        override fun startInjecting(language: com.intellij.lang.Language, extension: String?) = this
        override fun addPlace(
            prefix: String?,
            suffix: String?,
            host: com.intellij.psi.PsiLanguageInjectionHost,
            rangeInsideHost: TextRange,
        ): com.intellij.lang.injection.MultiHostRegistrar {
            places += rangeInsideHost
            affixes += prefix to suffix
            return this
        }

        override fun doneInjecting() = Unit
    }
}
