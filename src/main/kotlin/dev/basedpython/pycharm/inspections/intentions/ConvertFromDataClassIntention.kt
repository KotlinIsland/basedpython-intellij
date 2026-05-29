package dev.basedpython.pycharm.inspections.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Converts `data class X:` back to `class X:` when the caret is on the `class` keyword.
 * Reverse of [ConvertToDataClassIntention].
 */
class ConvertFromDataClassIntention : IntentionAction {

    override fun getText(): String = "Remove 'data' modifier (convert to plain class)"
    override fun getFamilyName(): String = "Convert from data class"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        return ConvertToDataClassIntention.findClassKeywordOffset(
            file.text, editor.caretModel.offset, expectDataClass = true
        ) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is BasedPythonFile) return
        val dataStart = ConvertToDataClassIntention.findClassKeywordOffset(
            file.text, editor.caretModel.offset, expectDataClass = true
        ) ?: return
        // Delete "data " (5 chars)
        WriteCommandAction.runWriteCommandAction(project, "Remove data modifier", null, {
            editor.document.deleteString(dataStart, dataStart + 5)
        }, file)
    }
}
