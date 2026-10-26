package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.OffsetMap
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.InsertTextFormat
import dev.basedpython.pycharm.lsp.completion.BySnippets.Part
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Smart completion's items are `by/smartCompletion`'s, read and written as the platform reads and
 * writes `textDocument/completion`'s: matched against what the edit replaces before the caret, drawn
 * as `by`'s others, and a snippet run as a live template.
 */
@TestFixtures
class BySmartCompletionTest {

    private val fixture by codeInsightFixture()

    /**
     * Chooses [item] at the caret of [text], `‸`, as the lookup does on Enter: what was typed of it
     * replaced with its lookup string, then its insert handler run, as completion runs it.
     */
    private fun accept(text: String, item: CompletionItem): String {
        val psi = fixture.configureByText("shapes.by", text.replace("‸", "<caret>"))
        val editor = fixture.editor
        val document = editor.document
        val caret = editor.caretModel.offset
        val element = BySmartCompletion.element(item, document, caret)
        val start = caret - BySmartCompletion.typedPrefix(document, caret, item).orEmpty().length
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            document.replaceString(start, caret, element.lookupString)
            editor.caretModel.moveToOffset(start + element.lookupString.length)
            val offsets = OffsetMap(document)
            offsets.addOffset(CompletionInitializationContext.START_OFFSET, start)
            offsets.addOffset(InsertionContext.TAIL_OFFSET, start + element.lookupString.length)
            element.handleInsert(InsertionContext(offsets, '\n', arrayOf(element), psi, editor, false))
        }
        TemplateManagerImpl.getTemplateState(editor)?.gotoEnd(false)
        val result = document.text
        val at = editor.caretModel.offset
        return result.substring(0, at) + "‸" + result.substring(at)
    }

    private fun edit(label: String, line: Int, from: Int, to: Int, text: String, snippet: Boolean = false) = CompletionItem(label).apply {
        textEdit = Either.forLeft(TextEdit(Range(Position(line, from), Position(line, to)), text))
        if (snippet) insertTextFormat = InsertTextFormat.Snippet
    }

    @Test
    fun `choosing an item writes its edit, a snippet's caret where its end stop is`() = onEdt {
        assertEquals("draw(make()‸)\n", accept("draw(‸)\n", edit("make", 0, 5, 5, "make()")))
        assertEquals("draw(Circle()‸)\n", accept("draw(Ci‸)\n", edit("Circle", 0, 5, 7, "Circle()")))
        assertEquals("draw(scale(‸))\n", accept("draw(sc‸)\n", edit("scale", 0, 5, 7, "scale(\$0)", snippet = true)))
    }

    @Test
    fun `a snippet's text and stops`() {
        assertEquals(
            listOf(Part.Text("for "), Part.Stop(1, "item"), Part.Text(" in xs:\n    "), Part.Stop(0, "")),
            BySnippets.parse("for \${1:item} in xs:\n    \$0"),
        )
        assertEquals(listOf(Part.Text("make("), Part.Stop(0, ""), Part.Text(")")), BySnippets.parse("make(\$0)"))
        assertEquals(listOf(Part.Text("f("), Part.Stop(1, "a"), Part.Text(", "), Part.Stop(2, "x"), Part.Text(")")), BySnippets.parse("f(\${1|a,b|}, \${2:\${3:x}})"))
        assertEquals(listOf(Part.Text("cost: \$5 {}")), BySnippets.parse("cost: \\\$5 {\\}"), "escapes")
    }

    @Test
    fun `an item is matched against what its edit replaces before the caret`() {
        val document = DocumentImpl("draw(Ci)\n")
        val item = CompletionItem("Circle").apply { textEdit = Either.forLeft(TextEdit(Range(Position(0, 5), Position(0, 7)), "Circle()")) }
        assertEquals("Ci", BySmartCompletion.typedPrefix(document, 7, item))
    }

    @Test
    fun `an item inserts its edit's text and is drawn by its label and detail`() {
        val document = DocumentImpl("draw()\n")
        val item = CompletionItem("make").apply {
            textEdit = Either.forLeft(TextEdit(Range(Position(0, 5), Position(0, 5)), "make()"))
            labelDetails = CompletionItemLabelDetails().apply { description = "def make() -> Circle" }
        }
        val element = BySmartCompletion.element(item, document, 5)
        assertEquals("make()", element.lookupString)
        assertEquals(setOf("make()", "make"), element.allLookupStrings)
        val presentation = LookupElementPresentation().also(element::renderElement)
        assertEquals("make", presentation.itemText)
        assertEquals("def make() -> Circle", presentation.typeText)
    }
}
