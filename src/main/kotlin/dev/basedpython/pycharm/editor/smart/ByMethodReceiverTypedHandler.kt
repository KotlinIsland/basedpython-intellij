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
 * Which of the three a `def`'s first parameter is — from decorators, `class def` and `static def`,
 * and the methods Python makes static or class methods by name, `__new__` the static method passed
 * its class — is `by`'s answer: `receiver` on the statement in `by/syntaxOutline`, `instance` or
 * `class`, and none for any other static method. A `by` that reports a method's `method` but not its
 * `receiver` is read by `method`, which says `static` for `__new__` too; a `def` that is not a method,
 * or no outline for the text on screen, leaves the `(` to the platform.
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
        val method = methodNamedAt(outline, offset) ?: return Result.CONTINUE
        // only a header still being written: nothing after the name on its line
        val lineEnd = document.getLineEndOffset(document.getLineNumber(offset))
        if (text.subSequence(offset, lineEnd).isNotBlank()) return Result.CONTINUE
        val receiver = when (method.receiver ?: method.method) {
            "instance" -> "self"
            "class" -> "cls"
            "static" -> ""
            else -> return Result.CONTINUE
        }
        val closing = if (CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) "):" else ""
        document.insertString(offset, "($receiver$closing")
        editor.caretModel.moveToOffset(offset + 1 + receiver.length)
        return Result.STOP
    }

    /** The method `def` whose name ends at [offset] and whose header has no `:` yet. */
    private fun methodNamedAt(outline: ByOutline, offset: Int): ByOutline.Statement? {
        var found: ByOutline.Statement? = null
        outline.walk { statement, _ ->
            val name = statement.name ?: return@walk
            if (name.endOffset != offset) return@walk
            if (statement.clauses.firstOrNull()?.colon != null) return@walk
            if (statement.method != null) found = statement
        }
        return found
    }
}
