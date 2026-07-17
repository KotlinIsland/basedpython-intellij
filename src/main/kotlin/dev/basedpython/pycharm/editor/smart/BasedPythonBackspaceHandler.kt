package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Backspace handler for basedpython (.by) files.
 *
 * When the caret is inside the leading indentation of a line (only whitespace precedes it on the
 * line) and the user presses Backspace, an entire indent step is removed — i.e. the caret snaps
 * back to the previous 4-column tab stop — instead of deleting a single space.
 *
 * Operates purely on the [com.intellij.openapi.editor.Document] text since the PSI is flat.
 */
class BasedPythonBackspaceHandler : BackspaceHandlerDelegate() {

    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        // No pre-deletion state needed.
    }

    /**
     * @return true if we fully handled the deletion (the platform must then NOT delete the char
     *         itself); false to let normal single-char backspace proceed.
     */
    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (!IndentLogic.isBasedPython(file)) return false
        // Only act when the char that is about to be removed is a plain space.
        if (c != ' ') return false

        val doc = editor.document
        // At this point the platform has NOT yet removed the char; caret is just after it.
        val caret = editor.caretModel.offset
        val lineNum = doc.getLineNumber(caret)
        val lineStart = doc.getLineStartOffset(lineNum)

        val text = doc.charsSequence
        // The caret must sit after the to-be-deleted space; everything from lineStart up to the
        // char before the caret must be whitespace (we're in the leading indentation).
        if (caret <= lineStart) return false
        for (i in lineStart until caret) {
            if (text[i] != ' ' && text[i] != '\t') return false
        }

        // Column of the char that would be deleted by a normal backspace (caret - 1).
        val column = (caret - 1) - lineStart
        // Snap to previous tab stop: delete back to the nearest lower multiple of INDENT_SIZE.
        val target = (column / IndentLogic.INDENT_SIZE) * IndentLogic.INDENT_SIZE
        val deleteFrom = lineStart + target
        // Default backspace would remove exactly one char (deleteFrom == caret - 1). If our snap
        // doesn't widen the deletion, defer to default behaviour.
        if (deleteFrom >= caret - 1) return false

        doc.deleteString(deleteFrom, caret)
        editor.caretModel.moveToOffset(deleteFrom)
        return true
    }
}
