package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.fillParagraph.ParagraphFillHandler
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.docs.render.ByDocstringSpans
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Edit | Fill Paragraph in a `.by` file: a run of `#` comments, or the paragraph of a docstring the
 * caret is in, re-wrapped at the right margin, as PyCharm does for a `.py`.
 *
 * A comment is the lexer's token. Which string is a docstring is `by`'s answer, the one rendered
 * docs use ([ByDocstringSpans]); a string `by` has not said is one is left alone.
 */
class ByParagraphFillHandler : ParagraphFillHandler() {

    override fun isAvailableForFile(psiFile: PsiFile?): Boolean = psiFile is BasedPythonFile

    override fun isAvailableForElement(element: PsiElement?): Boolean =
        element is PsiComment || element != null && isDocstring(element)

    override fun getPrefix(element: PsiElement): String = if (element is PsiComment) "# " else quotes(element)

    override fun getPostfix(element: PsiElement): String = if (element is PsiComment) "" else quotes(element)

    private fun isDocstring(element: PsiElement): Boolean {
        if (element.node.elementType != BasedPythonTokenTypes.STRING) return false
        val range = element.textRange
        return ByDocstringSpans.recorded(element.containingFile).orEmpty().any { it.range.contains(range) || range.contains(it.range) }
    }

    /** A docstring's opening quotes: `"""` or `'''`, after any prefix. */
    private fun quotes(element: PsiElement): String {
        val text = element.text.trimStart { it.isLetter() }
        return if (text.startsWith("'''")) "'''" else "\"\"\""
    }
}
