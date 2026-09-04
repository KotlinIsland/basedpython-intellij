package dev.basedpython.pycharm.lsp.inject

import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A fragment `by` reported, injected into the file for real: the host element, the injector, the
 * escaper and the platform's own machinery, end to end.
 *
 * The server is not here, and does not need to be — [ByInjections.remember] puts an answer in as
 * though it had just arrived, which is the whole of what a running `by` contributes. What is worth
 * testing is everything after that, because that is where a fragment ends up injected over the
 * wrong characters, or not at all.
 *
 * The injected language throughout is plain text. Which language is picked is
 * [ByInjectedLanguagesTest]'s question, and every IDE this plugin runs in has plain text, while
 * html belongs to a plugin that may not be on the test classpath.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class LanguageInjectionTest {

    private val fixture by codeInsightFixture()

    private val manager get() = InjectedLanguageManager.getInstance(fixture.project)

    private fun literalsIn(file: PsiFile): List<BasedPythonStringLiteral> =
        PsiTreeUtil.findChildrenOfType(file, BasedPythonStringLiteral::class.java).toList()

    /** The absolute content range of the [index]th literal — what `by` would have reported. */
    private fun contentRange(file: PsiFile, index: Int): TextRange =
        literalsIn(file)[index].let { it.contentRange.shiftRight(it.textRange.startOffset) }

    /** Configures [source] as a `.by` file and hands it [injections] as if the server had. */
    private fun inject(
        source: String,
        language: String = PlainTextLanguage.INSTANCE.id,
        parts: (PsiFile) -> List<TextRange> = { listOf(contentRange(it, 0)) },
    ): PsiFile {
        val file = fixture.configureByText("main.by", source)
        ByInjections.getInstance(fixture.project).remember(
            file,
            listOf(ByInjection(language, parts(file), ByInjectionOrigin.DECLARED)),
        )
        return file
    }

    /** The injected file covering [offset] of the host, or null when nothing is injected there. */
    private fun injectedAt(file: PsiFile, offset: Int): PsiFile? =
        manager.findInjectedElementAt(file, offset)?.containingFile

    // region: the fragment is injected

    @Test
    fun `a string the server reported becomes a document of that language`() {
        val file = inject("a = \"<b>hi</b>\"\n")
        val injected = injectedAt(file, contentRange(file, 0).startOffset + 1)

        assertNotNull(injected)
        assertEquals(PlainTextLanguage.INSTANCE, injected!!.language)
        assertEquals("<b>hi</b>", injected.text)
    }

    @Test
    fun `the injected language reads the text the string stands for, not the way it is spelled`() {
        val file = inject("a = \"<a href=\\\"/\\\">\"\n")
        val injected = injectedAt(file, contentRange(file, 0).startOffset + 1)

        // The escaped text is what the injected language is parsed from; the injected file's own
        // `text` stays as written, because the platform patches its leaves back to the host's
        // characters so that every offset in it still lines up with the `.by` file. Asking for the
        // unescaped text is how the two are told apart, and the decoded one is the one that has to
        // be right — it is what the other language's parser, highlighter and completion see.
        assertEquals("<a href=\"/\">", manager.getUnescapedText(injected!!))
        assertEquals("<a href=\\\"/\\\">", injected.text)
    }

    @Test
    fun `a fragment written as adjacent literals is one document`() {
        val file = inject("a = \"SELECT *\" \" FROM t\"") { listOf(contentRange(it, 0), contentRange(it, 1)) }
        val injected = injectedAt(file, contentRange(file, 0).startOffset + 1)

        // One document out of two literals, rather than two documents holding half a query each.
        assertEquals("SELECT * FROM t", injected?.text)
    }

    @Test
    fun `a triple-quoted string is injected across its lines`() {
        val file = inject("a = \"\"\"<b>\nhi\n</b>\"\"\"\n")
        val injected = injectedAt(file, contentRange(file, 0).startOffset + 1)

        assertEquals("<b>\nhi\n</b>", injected?.text)
    }

    @Test
    fun `a dedented triple-quoted string is injected without its indentation`() {
        // basedpython strips a triple-quoted string's incidental indentation, so `by` reports the
        // runs that survive it — one per line — rather than the block between the quotes.
        val source = "def render():\n    page = \"\"\"\n    <div>\n    asdf\n    </div>\n    \"\"\"\n"
        val file = fixture.configureByText("main.by", source)
        val runs = listOf("<div>\n", "asdf\n", "</div>").map {
            TextRange(source.indexOf(it), source.indexOf(it) + it.length)
        }
        ByInjections.getInstance(fixture.project).remember(
            file,
            listOf(ByInjection(PlainTextLanguage.INSTANCE.id, runs, ByInjectionOrigin.COMMENT)),
        )

        val injected = injectedAt(file, runs.first().startOffset + 1)
        assertEquals("<div>\nasdf\n</div>", injected?.let { manager.getUnescapedText(it) })

        // The shreds are what the editor shades and what an edit is written back through. One per
        // line, starting past the indentation — which is why the fragment reads as a block of html
        // rather than as a block of shading running to the left margin.
        val window = injected!!.viewProvider.document as DocumentWindow
        assertEquals(
            runs.map { it.startOffset to it.endOffset },
            window.hostRanges.map { it.startOffset to it.endOffset },
        )
    }

    // endregion

    // region: the fragment is left alone

    @Test
    fun `a string the server said nothing about is an ordinary string`() {
        val file = fixture.configureByText("plain.by", "a = \"<b>hi</b>\"\n")
        assertNull(injectedAt(file, 6))
    }

    @Test
    fun `a language this IDE does not have is not an error, it is no injection`() {
        val file = inject("a = \"x\"\n", language = "no-such-language-anywhere")
        assertNull(injectedAt(file, contentRange(file, 0).startOffset))
    }

    @Test
    fun `turning the setting off turns the feature off`() {
        val settings = BasedPythonSettings.getInstance(fixture.project)
        val was = settings.byLanguageInjection
        try {
            settings.byLanguageInjection = false
            val file = inject("a = \"<b>hi</b>\"\n")
            assertNull(injectedAt(file, contentRange(file, 0).startOffset + 1))
        } finally {
            settings.byLanguageInjection = was
        }
    }

    @Test
    fun `a fragment whose second part is not a string is dropped whole`() {
        // The answer is a revision out of date and its second range now lands on code. Injecting
        // the first half alone would be a fragment with a hole in it.
        val file = inject("a = \"SELECT *\" + name") { listOf(contentRange(it, 0), TextRange(17, 21)) }
        assertNull(injectedAt(file, contentRange(file, 0).startOffset + 1))
    }

    @Test
    fun `an f-string is never injected into, whatever the server says`() {
        val file = inject("a = f\"{x}\"\n")
        assertNull(injectedAt(file, contentRange(file, 0).startOffset))
    }

    // endregion

    // region: editing through the fragment

    @Test
    fun `the injected range is the literal's own content`() {
        val file = inject("a = \"<b>hi</b>\"\n")
        val host = literalsIn(file).first()
        val shreds = manager.getInjectedPsiFiles(host)

        assertNotNull(shreds)
        assertTrue(shreds!!.isNotEmpty())
        assertEquals(host.contentRange, shreds.first().second)
    }

    // endregion
}
