package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.editor.impl.DocumentImpl
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where a name `by` has just bound an expression to ends, so that its renaming starts on it: the end
 * of the edit that replaced the expression, moved by the edits before it. The edits are `by`'s own,
 * as it answered Introduce Variable, Introduce Constant and Introduce Attribute.
 */
class ByExtractedNameTest {

    private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = Range(Position(l1, c1), Position(l2, c2))

    /** [before] with [edits] made, where `|` is the offset [ByExtractedName.endAfter] answered. */
    private fun marked(before: String, edits: List<TextEdit>, replaced: Range): String {
        val end = checkNotNull(ByExtractedName.endAfter(DocumentImpl(before), edits, replaced))
        val lineStarts = listOf(0) + before.indices.filter { before[it] == '\n' }.map { it + 1 }
        fun offset(position: Position) = lineStarts[position.line] + position.character
        var after = before
        for (edit in edits.sortedByDescending { offset(it.range.start) }) {
            after = after.substring(0, offset(edit.range.start)) + edit.newText + after.substring(offset(edit.range.end))
        }
        return after.substring(0, end) + "|" + after.substring(end)
    }

    @Test
    fun `a variable assigned on a line above`() {
        val source = "def area(scale, radius):\n    total = scale * math.pi * radius ** 2\n"
        val replaced = range(1, 30, 1, 41)
        val edits = listOf(
            TextEdit(range(1, 0, 1, 0), "    value = radius ** 2\n"),
            TextEdit(replaced, "value"),
        )

        assertEquals(
            "def area(scale, radius):\n    value = radius ** 2\n    total = scale * math.pi * value|\n",
            marked(source, edits, replaced),
        )
    }

    @Test
    fun `an attribute, whose read ends with its name`() {
        val source = "class Box:\n    def size(self):\n        return self.width * 2 + 3\n"
        val replaced = range(2, 15, 2, 33)
        val edits = listOf(
            TextEdit(range(2, 0, 2, 0), "        self.value = self.width * 2 + 3\n"),
            TextEdit(replaced, "self.value"),
        )

        assertEquals(
            "class Box:\n    def size(self):\n        self.value = self.width * 2 + 3\n        return self.value|\n",
            marked(source, edits, replaced),
        )
    }

    @Test
    fun `a constant hoisted to the top of the module, and an edit after the expression that moves nothing`() {
        val source = "import math\n\n\ndef f(x):\n    return x * 2 + 3\n"
        val replaced = range(4, 15, 4, 16)
        val edits = listOf(
            TextEdit(range(1, 0, 1, 0), "\nNUMBER = 2\n"),
            TextEdit(replaced, "NUMBER"),
            TextEdit(range(4, 19, 4, 20), "3"),
        )

        assertEquals(
            "import math\n\nNUMBER = 2\n\n\ndef f(x):\n    return x * NUMBER| + 3\n",
            marked(source, edits, replaced),
        )
    }

    @Test
    fun `no edit replacing exactly the expression is no name`() {
        val document = DocumentImpl("x = a + b\n")
        val edits = listOf(TextEdit(range(0, 4, 0, 9), "value"))

        assertNull(ByExtractedName.endAfter(document, edits, range(0, 4, 0, 5)))
    }
}
