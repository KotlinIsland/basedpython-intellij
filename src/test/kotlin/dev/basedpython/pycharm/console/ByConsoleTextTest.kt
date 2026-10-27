package dev.basedpython.pycharm.console

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.editor.indent.ByIndents
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What a basedpython console sends `by repl` and does to its own input: the framing an input is
 * written in, what Enter asks and inserts, how an editor's selection is sent, which `<by-input-N>`
 * names it links, and how `by`'s completion over the session module lands on the input.
 */
class ByConsoleTextTest {

    @Test
    fun `an input is framed as a paste and ends the line, blank lines inside a body and all`() {
        assertEquals(
            "\u001b[200~def f():\n    a = 1\n\n    return a\u001b[201~\n",
            ByConsoleText.framed("def f():\n    a = 1\n\n    return a"),
        )
    }

    @Test
    fun `a blank input is not one, as by repl skips it unnumbered`() {
        assertFalse(ByConsoleText.isInput("  \n\t"))
    }

    @Test
    fun `Enter asks about the text with the line break it adds`() {
        // by/inputStatus reads lines as typed at a terminal, each ending in its newline: a body after
        // a whitespace-only line is complete only once that line is ended
        assertEquals("def f():\n    return 1\n    \n", ByConsoleText.statusText("def f():\n    return 1\n    "))
    }

    @Test
    fun `a selection is sent without the indentation its lines share`() {
        assertEquals("total = 0\nfor i in range(n):\n    total += i\n\nreturn total", ByConsoleText.dedented("    total = 0\n    for i in range(n):\n        total += i\n  \n    return total"))
        assertEquals("x = 1\n  y", ByConsoleText.dedented("x = 1\n  y"))
    }

    @Test
    fun `a line break worked out for the session module is made in the input`() {
        val session = "let rate = 0.2\n"
        val input = "def f():"
        val edit = ByConsoleText.broken(input, input.length, session.length, ByIndents.LineBreak(indent = 4)) { " ".repeat(it) }
        assertEquals(ByConsoleText.Edit("def f():\n    ", 13), edit)
    }

    @Test
    fun `a split string is reopened on the new line and wrapped where the input is`() {
        val session = "x = 1\n"
        val input = "s = 'ab'"
        // by's answer is about the module: the string's run starts at 4 in the input, 10 in the module
        val lineBreak = ByIndents.LineBreak(indent = 5, before = "'", after = "'", wrap = TextRange(10, 14))
        val edit = ByConsoleText.broken(input, 6, session.length, lineBreak) { " ".repeat(it) }
        assertEquals("s = ('a'\n     'b')", edit.text)
        assertEquals("s = ('a'\n     '".length, edit.caret)
    }

    @Test
    fun `without by's answer the new line keeps the current line's indentation`() {
        assertEquals(ByConsoleText.Edit("if x:\n    y\n    ", 16), ByConsoleText.brokenPlainly("if x:\n    y", 11))
    }

    @Test
    fun `tracebacks and diagnostics name inputs by number, line and column`() {
        val frame = """  File "<by-input-6>", line 2, in ratio"""
        assertEquals(
            listOf(ByConsoleText.InputLink(TextRange(8, 29), 6, 2, 1)),
            ByConsoleText.inputLinks(frame),
        )
        assertEquals(
            listOf(ByConsoleText.InputLink(TextRange(5, 21), 4, 1, 3)),
            ByConsoleText.inputLinks(" --> <by-input-4>:1:3"),
        )
        assertEquals(emptyList<ByConsoleText.InputLink>(), ByConsoleText.inputLinks("""  File "main.by", line 2"""))
    }

    @Test
    fun `completion over the session module lands on the input, an import before it at its start`() {
        val session = "let rate = 0.2\n"
        val input = DocumentImpl("ra")
        val module = DocumentImpl(session + input.text)
        val item = CompletionItem("rate").apply {
            textEdit = Either.forLeft(TextEdit(Range(Position(1, 0), Position(1, 2)), "rate"))
            additionalTextEdits = listOf(
                TextEdit(Range(Position(0, 0), Position(0, 0)), "import x\n"),
                // a rewrite of the session's own text: nothing the console can make
                TextEdit(Range(Position(0, 0), Position(0, 3)), "var"),
            )
        }
        val moved = ByConsoleCompletionContributor.onInput(item, module, session.length, input)!!
        assertEquals(Range(Position(0, 0), Position(0, 2)), moved.textEdit.left.range)
        assertEquals(listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), "import x\n")), moved.additionalTextEdits)
    }

    @Test
    fun `an item whose own edit is in the session is not offered`() {
        val session = "let rate = 0.2\n"
        val item = CompletionItem("let").apply { textEdit = Either.forLeft(TextEdit(Range(Position(0, 0), Position(0, 3)), "let")) }
        assertNull(ByConsoleCompletionContributor.onInput(item, DocumentImpl(session + "r"), session.length, DocumentImpl("r")))
    }
}
