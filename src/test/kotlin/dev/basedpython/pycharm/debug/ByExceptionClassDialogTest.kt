package dev.basedpython.pycharm.debug

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The class names an exception breakpoint is offered, out of `by`'s completion items as `by`
 * caabe9cac answers them: a class it would import is named by the module the import says.
 */
class ByExceptionClassDialogTest {

    private fun item(label: String, kind: CompletionItemKind, import: String? = null) = CompletionItem(label).apply {
        this.kind = kind
        if (import != null) additionalTextEdits = listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), import))
    }

    @Test
    fun `a builtin class is its name`() {
        assertEquals("KeyError", ByExceptionClassDialog.className(item("KeyError", CompletionItemKind.Class)))
    }

    @Test
    fun `a class by would import is the module and the class`() {
        assertEquals(
            "errs.AppError",
            ByExceptionClassDialog.className(item("AppError (import errs)", CompletionItemKind.Class, "from errs import AppError\n")),
        )
    }

    @Test
    fun `what is not a class is not offered`() {
        assertNull(ByExceptionClassDialog.className(item("keyword (import keyword)", CompletionItemKind.Module, "import keyword\n")))
        assertNull(ByExceptionClassDialog.className(item("KEY_ENTER (import curses)", CompletionItemKind.Constant, "from curses import KEY_ENTER\n")))
    }
}
