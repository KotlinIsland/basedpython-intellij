package dev.basedpython.pycharm.debug

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.util.TextRange
import com.jetbrains.dap.protocol.CompletionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How an adapter's `completions` answer becomes what a field offers and what picking one does. */
class ByDapCompletionItemsTest {

    @Test
    fun `the caret is sent as a 1-based line and column`() {
        assertEquals(1 to 1, ByDapCompletionItems.caretPosition("", 0))
        assertEquals(1 to 9, ByDapCompletionItems.caretPosition("sys.vers", 8))
        assertEquals(2 to 3, ByDapCompletionItems.caretPosition("a +\nb.c", 6))
    }

    @Test
    fun `start counts from 0 on the caret's line, as debugpy and VS Code count it`() {
        // debugpy's answer for `sys.vers`, measured: start=4, length=4 is the `vers`
        val spec = ByDapCompletionItems.specs("sys.vers", 8, listOf(CompletionItem(label = "version", start = 4, length = 4))).single()
        assertEquals("vers", spec.prefix)
        assertEquals("vers", spec.matchPrefix)
        assertEquals(0, spec.prefixDeleteLength)
        // on a second line, the column is the line's
        val second = ByDapCompletionItems.specs("x\nsys.ve", 8, listOf(CompletionItem(label = "version", start = 4, length = 2))).single()
        assertEquals("ve", second.prefix)
    }

    @Test
    fun `a start past the caret or with none inserts at the caret`() {
        val specs = ByDapCompletionItems.specs(
            "sys.",
            4,
            listOf(CompletionItem(label = "a", start = 9), CompletionItem(label = "b")),
        )
        assertEquals(listOf("", ""), specs.map { it.prefix })
    }

    @Test
    fun `a start before the identifier replaces what lies between, beyond what the lookup matches`() {
        val spec = ByDapCompletionItems.specs("d[k", 3, listOf(CompletionItem(label = "['key']", start = 1, length = 2))).single()
        assertEquals("[k", spec.prefix)
        assertEquals("k", spec.matchPrefix)
        assertEquals(1, spec.prefixDeleteLength)
    }

    @Test
    fun `items are ordered by sortText, else label, and one with nothing to insert is dropped`() {
        val specs = ByDapCompletionItems.specs(
            "",
            0,
            listOf(
                CompletionItem(label = "b"),
                CompletionItem(label = "z", sortText = "a"),
                CompletionItem(label = ""),
                CompletionItem(label = "c", text = "c()"),
            ),
        )
        assertEquals(listOf("z", "b", "c()"), specs.map { it.insertText })
    }

    @Test
    fun `what the adapter replaces after the caret stops at the line's end, and Tab leaves the identifier to the lookup`() {
        val spec = ByDapCompletionItems.specs("a.bcd + 1\nx", 3, listOf(CompletionItem(label = "bcdef", start = 2, length = 20))).single()
        assertEquals(6, spec.tailReplaceLength)
        assertEquals(2, spec.identifierTailLength)
        assertEquals(6, spec.tailDeleteLength(Lookup.NORMAL_SELECT_CHAR))
        assertEquals(4, spec.tailDeleteLength(Lookup.REPLACE_SELECT_CHAR))
    }

    @Test
    fun `the selection the adapter asks for is kept within the inserted text, and is the end without one`() {
        val asked = ByDapCompletionItems.specs("", 0, listOf(CompletionItem(label = "f(x)", selectionStart = 2, selectionLength = 9))).single()
        assertEquals(TextRange(2, 4), asked.selectionInInsertText())
        val none = ByDapCompletionItems.specs("", 0, listOf(CompletionItem(label = "f"))).single()
        assertEquals(TextRange(1, 1), none.selectionInInsertText())
    }
}
