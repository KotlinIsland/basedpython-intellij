package dev.basedpython.pycharm.editor.djangotemplate

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.folding.ByFoldingRanges

/**
 * Folding for a django template `by` serves: `by`'s `textDocument/foldingRange`, one range for each
 * `{% block %}`, `{% if %}`, `{% for %}` and every other tag with an end tag, folded to the text `by`
 * gives, or `...`.
 *
 * Which tag opens a range and where it ends is `by`'s template parse; this only asks, through
 * [ByFoldingRanges] as `.by` folding does, so the first pass that opens the editor waits for the
 * answer and a template opened before `by` is up is folded again once it is. The file's own HTML
 * folding — tags, comments — stays beside it.
 *
 * Registered for HTML, XHTML, XML and plain text, each on its own: a folding builder is found for
 * the language it is registered for alone, not for the languages based on it.
 */
class ByTemplateFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return FoldingDescriptor.EMPTY_ARRAY
        if (!ByDjangoTemplateEditing.handles(file)) return FoldingDescriptor.EMPTY_ARRAY
        val ranges = ByFoldingRanges.getInstance(file.project)
        val folds = (if (quick) ranges.current(document) else ranges.forFile(file)) ?: return FoldingDescriptor.EMPTY_ARRAY
        return folds
            .filter { it.range.endOffset <= document.textLength }
            .map { fold -> FoldingDescriptor(file.node, fold.range, null, fold.collapsedText ?: ELLIPSIS, false, emptySet()) }
            .toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = ELLIPSIS

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private companion object {
        const val ELLIPSIS = "..."
    }
}
