package dev.basedpython.pycharm.docs.render

import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * A docstring, presented to the platform as the doc comment it has no node for.
 *
 * Rendered documentation is collected as [PsiDocCommentBase] elements, and `.by` has none: its PSI
 * is flat, and a docstring is a string literal rather than a comment in the first place. The
 * contract for `DocumentationProvider.collectDocComments` allows exactly this — elements that do
 * not exist in the file, as long as `findDocComment` can hand back an equal one for a range — and
 * that is what this is. Nothing else in the plugin sees it; it lives for the length of one
 * rendering pass.
 *
 * [getOwner] answers with the leaf holding the documented symbol's name, which is what puts the
 * gutter control beside the definition rather than the file. A module docstring has no such leaf
 * and answers `null`, which the platform reads as "no owner" rather than "the whole file".
 */
internal class ByDocstringComment(
    private val file: PsiFile,
    val docstring: ByDocstring,
) : FakePsiElement(), PsiDocCommentBase {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getLanguage(): Language = BasedPythonLanguage

    /** A docstring is a string, and saying so is truer than borrowing a comment token. */
    override fun getTokenType(): IElementType = BasedPythonTokenTypes.STRING

    override fun getTextRange(): TextRange = docstring.range

    override fun getTextOffset(): Int = docstring.range.startOffset

    override fun getTextLength(): Int = docstring.range.length

    override fun getText(): String = docstring.range.substring(file.text)

    override fun getName(): String? = null

    override fun getOwner(): PsiElement? =
        docstring.ownerNameOffset?.let { file.findElementAt(it) }

    override fun isValid(): Boolean =
        file.isValid && docstring.range.endOffset <= file.textLength
}
