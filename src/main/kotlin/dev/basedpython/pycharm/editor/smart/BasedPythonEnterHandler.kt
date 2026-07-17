package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Enter handler for basedpython (.by) files.
 *
 * When Enter is pressed:
 *  - if the line that the caret left ends with `:` (a block header such as `def f():`, `if x:`,
 *    `else:`, `try:`, `case ...:`), the freshly created line is indented one level (4 spaces)
 *    deeper than that header line;
 *  - otherwise the new line simply inherits the previous line's leading indentation.
 *
 * All work is done against the [com.intellij.openapi.editor.Document] text (offsets/lines) because
 * the basedpython PSI is flat (token-only).
 */
class BasedPythonEnterHandler : EnterHandlerDelegateAdapter() {

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (!IndentLogic.isBasedPython(file)) return EnterHandlerDelegate.Result.Continue

        val doc = editor.document
        val caret = editor.caretModel.offset
        val newLineNum = doc.getLineNumber(caret)
        // The header / previous line is the one before the caret's current line.
        if (newLineNum == 0) return EnterHandlerDelegate.Result.Continue

        val prevLineNum = newLineNum - 1
        val prevStart = doc.getLineStartOffset(prevLineNum)
        // Compute the intended indent from the previous (header or normal) line.
        val desiredIndent = IndentLogic.newLineIndent(doc, prevStart)

        // Replace whatever leading whitespace the platform may have inserted on the new line
        // with our desired indent, and position the caret at the end of that indent.
        val newLineStart = doc.getLineStartOffset(newLineNum)
        val existingIndentEnd = run {
            var i = newLineStart
            val len = doc.textLength
            val text = doc.charsSequence
            while (i < len && (text[i] == ' ' || text[i] == '\t')) i++
            i
        }
        doc.replaceString(newLineStart, existingIndentEnd, desiredIndent)
        PsiDocumentManager.getInstance(file.project).commitDocument(doc)
        editor.caretModel.moveToOffset(newLineStart + desiredIndent.length)

        return EnterHandlerDelegate.Result.Stop
    }
}
