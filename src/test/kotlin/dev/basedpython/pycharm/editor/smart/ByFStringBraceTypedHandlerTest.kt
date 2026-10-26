package dev.basedpython.pycharm.editor.smart

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/** Typing braces inside a string literal, with `by`'s outline of the text put in by hand. */
@TestFixtures
class ByFStringBraceTypedHandlerTest {

    private val fixture by codeInsightFixture()

    private fun type(c: Char, before: String, after: String, outline: (OutlineSpec.Suite.() -> Unit)?) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.type(c)
        fixture.checkResult(after)
    }

    @Test
    fun `a brace typed in an f-string's text gets its pair`() = type('{', "x = f\"a <caret>\"\n", "x = f\"a {<caret>}\"\n") {
        simple("x = f\"a \"")
        string("f\"a \"")
    }

    @Test
    fun `a t-string's too`() = type('{', "x = t\"<caret>\"\n", "x = t\"{<caret>}\"\n") {
        simple("x = t\"\"")
        string("t\"\"")
    }

    @Test
    fun `a brace typed inside a pair makes the escaped pair`() = type('{', "x = f\"{<caret>}\"\n", "x = f\"{{<caret>}}\"\n") {
        simple("x = f\"{}\"")
        string("f\"{}\"", interpolations = listOf("{}"))
    }

    @Test
    fun `the closing brace of an interpolation is typed over`() = type('}', "x = f\"{y<caret>}\"\n", "x = f\"{y}<caret>\"\n") {
        simple("x = f\"{y}\"")
        string("f\"{y}\"", interpolations = listOf("{y}"))
    }

    @Test
    fun `a plain string's brace is only a character`() = type('{', "x = \"<caret>\"\n", "x = \"{<caret>\"\n") {
        simple("x = \"\"")
        string("\"\"")
    }

    /**
     * No outline for the text on screen is the platform's typing, which pairs the brace too: the
     * editor's lexer reads the `{` as a field's ([dev.basedpython.pycharm.lang.BasedPythonHighlightingLexer]).
     */
    @Test
    fun `without an answer for this text, the platform pairs the brace`() =
        type('{', "x = f\"<caret>\"\n", "x = f\"{<caret>}\"\n", outline = null)
}
