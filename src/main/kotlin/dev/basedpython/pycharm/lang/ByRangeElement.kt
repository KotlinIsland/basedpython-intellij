package dev.basedpython.pycharm.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement

/**
 * A stretch of a `.by` file, as a `PsiElement`, for a platform API that asks for elements where
 * `by` answers with ranges.
 *
 * A `.by` file's tree is one leaf per token (see [BasedPythonParserDefinition]), so it has no
 * element for an argument or a suite. The APIs this stands in for — Move Element Left/Right, the
 * Unwrap/Remove popup — read nothing of an element but where it is, and that is all this has. It
 * holds the file and two offsets, and is made afresh each time one is asked for; nothing keeps it.
 */
internal open class ByRangeElement(private val file: PsiFile, private val range: TextRange) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextRange(): TextRange = range

    override fun getTextOffset(): Int = range.startOffset

    override fun getTextLength(): Int = range.length

    override fun getText(): String? =
        file.viewProvider.document?.immutableCharSequence
            ?.takeIf { range.endOffset <= it.length }
            ?.let { range.subSequence(it).toString() }

    override fun isValid(): Boolean = file.isValid

    override fun toString(): String = "ByRangeElement$range"
}
