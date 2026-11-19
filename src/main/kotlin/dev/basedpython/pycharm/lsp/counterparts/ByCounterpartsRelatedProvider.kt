package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByCounterpartDeclaration
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Range

/**
 * Go to Related Symbol between a stub and its runtime module, from `by/documentCounterparts` as the
 * gutter's icons are ([ByCounterparts], the same answer): the other module, from anywhere in this
 * one, and the counterparts of the declaration the caret is on.
 *
 * The other module is listed always, which is how the module itself — with no name to put an icon on
 * — is jumped to its stub or back.
 *
 * A counterpart is offered as the element that declares it when the other file's tree has one — a
 * `PyFunction`, `PyClass` or `PyTargetExpression` in PyCharm, the same elements its own stub icon
 * offers, as a plain `GotoRelatedItem` as its are, so that its entry and this one are one entry in the
 * popup (an item equals only one of its own class on the same element), and this one, being asked
 * first, is the one kept: measured in PyCharm 2026.3, a subclass of the item listed `f` twice. In a
 * file with no such tree (a `.by`, a TextMate `.pyi`) it is the name's token, named by the
 * declaration's dotted path, since a token has no presentation of its own.
 *
 * The platform collects related items in a background read action, which is where [ByCounterparts]
 * may wait on `by`.
 */
class ByCounterpartsRelatedProvider : GotoRelatedProvider() {

    override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        // asking waits on `by`, which the action does off the EDT; a caller on it gets nothing
        if (ApplicationManager.getApplication().isDispatchThread) return emptyList()
        val file = psiElement.containingFile ?: return emptyList()
        val project = file.project
        val counterparts = ByCounterparts.getInstance(project).forFile(file) ?: return emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val server = byServerFor(project, virtualFile)
        val group = BasedPythonBundle.message(if (counterparts.isStub) "counterpart.related.implementation" else "counterpart.related.stub")
        val psiManager = PsiManager.getInstance(project)
        // the server's reading of its own URIs, and the VFS's for a server gone since it answered
        val find = { uri: String ->
            (server?.descriptor?.findFileByUri(uri) ?: VirtualFileManager.getInstance().findFileByUrl(uri))?.takeIf { it.isValid }
        }

        val items = ArrayList<GotoRelatedItem>()
        val caret = if (psiElement is PsiFile) null else psiElement.textRange?.startOffset
        if (caret != null) {
            for (declaration in at(counterparts, document, caret)) {
                for (target in declaration.targets.orEmpty()) {
                    val uri = target.uri ?: continue
                    val range = target.selectionRange ?: target.range ?: continue
                    val targetFile = find(uri) ?: continue
                    val element = declarationAt(psiManager.findFile(targetFile) ?: continue, targetFile, range) ?: continue
                    // the element that declares it is named by its own presentation, and is then the same
                    // item as PyCharm's (an item is equal to one of its own class only); a token is named here
                    items += if (element is PsiNameIdentifierOwner) {
                        GotoRelatedItem(element, group)
                    } else {
                        Counterpart(element, group, declaration.name, targetFile.name)
                    }
                }
            }
        }
        for (module in counterparts.counterparts.orEmpty()) {
            val targetFile = module.uri?.let(find) ?: continue
            items += GotoRelatedItem(psiManager.findFile(targetFile) ?: continue, group)
        }
        return items
    }

    /** A declaration's counterpart, named by the declaration's dotted path, in its file. */
    private class Counterpart(element: PsiElement, group: String, private val name: String?, private val fileName: String) :
        GotoRelatedItem(element, group) {
        override fun getCustomName(): String? = name

        override fun getCustomContainerName(): String = fileName
    }

    internal companion object {

        /**
         * The declarations of [counterparts] [caret] is in, innermost first: each declaration's
         * [ByCounterpartDeclaration.range] is the whole of it, decorators and body included, so a
         * method's body has the method and its class. A declaration whose range no longer fits the
         * document — the answer raced an edit — is in nothing.
         */
        fun at(counterparts: ByDocumentCounterparts, document: Document, caret: Int): List<ByCounterpartDeclaration> =
            counterparts.declarations.orEmpty()
                .mapNotNull { declaration ->
                    val whole = declaration.range?.let { offsets(document, it) } ?: return@mapNotNull null
                    declaration.takeIf { caret in whole.first..whole.second }?.let { it to whole.second - whole.first }
                }
                .sortedBy { it.second }
                .map { it.first }

        private fun offsets(document: Document, range: Range): Pair<Int, Int>? {
            val start = getOffsetInDocument(document, range.start) ?: return null
            val end = getOffsetInDocument(document, range.end) ?: return null
            return start to end
        }

        /**
         * What declares the name at [range] of [file]: the element whose name it is when the tree has
         * one, otherwise the name's token, or — in a tree with no tokens, a TextMate or plain-text
         * stub whose one leaf is the whole file — the name's own stretch of it, so that going there
         * lands on the name rather than at the top of the file.
         */
        fun declarationAt(file: PsiFile, virtualFile: VirtualFile, range: Range): PsiElement? {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
            val offset = getOffsetInDocument(document, range.start) ?: return null
            val end = getOffsetInDocument(document, range.end)?.takeIf { it > offset } ?: (offset + 1).coerceAtMost(document.textLength)
            val leaf = file.findElementAt(offset) ?: return null
            val owner = PsiTreeUtil.getParentOfType(leaf, PsiNameIdentifierOwner::class.java, false)
            owner?.takeIf { it.nameIdentifier?.textRange?.contains(offset) == true }?.let { return it }
            return if (leaf.textRange == TextRange(offset, end)) leaf else ByRangeElement(file, TextRange(offset, end))
        }
    }
}
