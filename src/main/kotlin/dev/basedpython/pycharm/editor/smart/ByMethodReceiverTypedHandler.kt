package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * `(` typed after a method's name in a `.by` class body writes its first parameter, as PyCharm's
 * *Insert 'self' when defining a method* does for `.py`: `def foo(` becomes `def foo(self):` with the
 * caret after `self`, `cls` for a class method and for `__new__`, nothing for another static one.
 *
 * Which of the three a `def` is — decorators, `class def` and `static def`, and the methods Python
 * makes static or class methods by name — is `by`'s answer (`method` on the statement in
 * `by/syntaxOutline`). A `by` that does not report it, a `def` that is not a method, or no outline
 * for the text on screen leaves the `(` to the platform.
 *
 * `__new__` is the one static method Python passes a class: `by` reports it `static`, which is how
 * the class dispatches it, and the class it is called with is its first parameter all the same —
 * `cls`, as `.py` writes.
 */
class ByMethodReceiverTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (c != '(') return Result.CONTINUE
        if (file !is BasedPythonFile || editor.selectionModel.hasSelection() || editor.caretModel.caretCount > 1) {
            return Result.CONTINUE
        }
        val document = editor.document
        val outline = ByOutlines.getInstance(project).current(document) ?: return Result.CONTINUE
        val offset = editor.caretModel.offset
        val text = document.immutableCharSequence
        val (method, name) = methodNamedAt(outline, offset) ?: return Result.CONTINUE
        // only a header still being written: nothing after the name on its line
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        if (text.subSequence(offset, lineEnd).isNotBlank()) return Result.CONTINUE
        val receiver = when (method) {
            "instance" -> "self"
            "class" -> "cls"
            "static" -> if (text.subSequence(name.startOffset, name.endOffset).contentEquals("__new__")) "cls" else ""
            else -> return Result.CONTINUE
        }
        val closing = if (CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) "):" else ""
        document.insertString(offset, "($receiver$closing")
        editor.caretModel.moveToOffset(offset + 1 + receiver.length)
        return Result.STOP
    }

    /** The `method` and name of the `def` whose name ends at [offset] and whose header has no `:` yet. */
    private fun methodNamedAt(outline: ByOutline, offset: Int): Pair<String, TextRange>? {
        var found: Pair<String, TextRange>? = null
        outline.walk { statement, _ ->
            val name = statement.name ?: return@walk
            if (name.endOffset != offset) return@walk
            if (statement.clauses.firstOrNull()?.colon != null) return@walk
            found = statement.method?.let { it to name }
        }
        return found
    }
}
