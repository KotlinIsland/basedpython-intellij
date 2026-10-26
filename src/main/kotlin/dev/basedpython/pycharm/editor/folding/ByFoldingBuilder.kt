package dev.basedpython.pycharm.editor.folding

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.CustomFoldingBuilder
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.folding.ByFoldingRanges

/**
 * Folding for `.by`: `by`'s folding ranges ([ByFoldingRanges]), folded to the text `by` gives and
 * folded by default as *Settings | Editor | General | Code Folding* says — imports, documentation
 * comments (a docstring) and method bodies by the platform's options, long strings, long
 * collections, comment runs and type annotations by basedpython's ([ByFoldingSettings]).
 *
 * `# region` and `# endregion` are the platform's custom folding, read off the comments as every
 * language's are: that is what makes *Navigate | Custom Folding* list them, and Surround With wrap
 * code in them. `by`'s `region` ranges, the same ranges, are left out.
 */
class ByFoldingBuilder : CustomFoldingBuilder() {

    override fun buildLanguageFoldRegions(
        descriptors: MutableList<FoldingDescriptor>,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ) {
        val file = root as? PsiFile ?: return
        val ranges = ByFoldingRanges.getInstance(file.project)
        val folds = (if (quick) ranges.current(document) else ranges.forFile(file)) ?: return
        val folded = folds.filter { it.kind != REGION && it.range.endOffset <= document.textLength }
        for (fold in folded) {
            descriptors += FoldingDescriptor(
                file.node,
                fold.range,
                null,
                fold.collapsedText ?: ELLIPSIS,
                collapsedByDefault(fold, folded),
                emptySet(),
            )
        }
    }

    /**
     * Whether *Fold by default* folds [fold]. A `function` range that holds another ending where it
     * does is a signature spanning lines together with the body: folded by default it would hide
     * the signature, so only the body is.
     */
    private fun collapsedByDefault(fold: ByFoldingRanges.Fold, all: List<ByFoldingRanges.Fold>): Boolean {
        val settings = CodeFoldingSettings.getInstance()
        val basedpython = ByFoldingSettings.getInstance().state
        return when (fold.kind) {
            "imports" -> settings.COLLAPSE_IMPORTS
            "docstring" -> settings.COLLAPSE_DOC_COMMENTS
            "function" -> settings.COLLAPSE_METHODS && all.none {
                it !== fold && it.kind == "function" && it.range.endOffset == fold.range.endOffset &&
                    fold.range.contains(it.range) && it.range != fold.range
            }
            "string" -> basedpython.collapseLongStrings
            "collection" -> basedpython.collapseLongCollections
            "comment" -> basedpython.collapseSequentialComments
            "annotation" -> basedpython.collapseTypeAnnotations
            else -> false
        }
    }

    override fun getLanguagePlaceholderText(node: ASTNode, range: TextRange): String = ELLIPSIS

    /** Every range of `by`'s carries whether it is folded by default; this is asked of none of them. */
    override fun isRegionCollapsedByDefault(node: ASTNode): Boolean = false

    private companion object {
        const val REGION = "region"
        const val ELLIPSIS = "..."
    }
}
