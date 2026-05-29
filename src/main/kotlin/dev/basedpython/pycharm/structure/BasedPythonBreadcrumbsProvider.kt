package dev.basedpython.pycharm.structure

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.structure.IndentScanner.NodeKind
import dev.basedpython.pycharm.structure.IndentScanner.ScopeNode

/**
 * Breadcrumbs for BasedPython (.by) files.
 *
 * Because the PSI tree is flat (all tokens are direct children of the file node),
 * we cannot rely on PSI parent traversal. Instead:
 *  - [acceptElement] returns true for [ScopeProxy] elements and for any leaf
 *    PSI element whose containing file is a [BasedPythonFile] (the latter allows
 *    the breadcrumbs infrastructure to call [getParent] to start building the chain).
 *  - [getParent] wraps scope nodes as [ScopeProxy]s for the breadcrumbs chain.
 */
class BasedPythonBreadcrumbsProvider : BreadcrumbsProvider {

    override fun getLanguages(): Array<Language> = arrayOf(BasedPythonLanguage)

    // -----------------------------------------------------------------------
    // BreadcrumbsProvider contract
    // -----------------------------------------------------------------------

    override fun acceptElement(element: PsiElement): Boolean =
        element is ScopeProxy || element.containingFile is BasedPythonFile

    override fun getElementInfo(element: PsiElement): String =
        (element as? ScopeProxy)?.node?.name ?: (element.containingFile?.name ?: "")

    override fun getParent(element: PsiElement): PsiElement? {
        if (element !is ScopeProxy) {
            // Leaf element: find the innermost enclosing scope
            val file = element.containingFile as? BasedPythonFile ?: return null
            val offset = element.textRange?.startOffset ?: return null
            val chain = buildChain(file, offset)
            return chain.lastOrNull()?.let { ScopeProxy(file, it, chain, chain.size - 1) }
        }
        val prev = element.chainIndex - 1
        return if (prev >= 0) ScopeProxy(element.file, element.chain[prev], element.chain, prev) else null
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun buildChain(file: BasedPythonFile, offset: Int): List<ScopeNode> {
        val text = file.text ?: return emptyList()
        val roots = IndentScanner.buildTree(text)
        val chain = mutableListOf<ScopeNode>()
        fun walk(nodes: List<ScopeNode>) {
            for (node in nodes) {
                if (node.kind == NodeKind.FIELD || node.kind == NodeKind.IMPORT_BLOCK) continue
                if (offset >= node.startOffset && offset < node.endOffset) {
                    chain += node
                    walk(node.children)
                    return
                }
            }
        }
        walk(roots)
        return chain
    }

    // -----------------------------------------------------------------------
    // Synthetic wrapper element
    // -----------------------------------------------------------------------

    /**
     * Synthetic [PsiElement] wrapper around a [ScopeNode] so the breadcrumbs
     * API can traverse the parent chain without a composite PSI tree.
     */
    class ScopeProxy(
        val file: BasedPythonFile,
        val node: ScopeNode,
        val chain: List<ScopeNode>,
        val chainIndex: Int,
    ) : com.intellij.psi.impl.FakePsiElement() {

        override fun getParent(): PsiElement = file
        override fun getContainingFile() = file
        override fun getProject() = file.project
        override fun isValid() = file.isValid
        override fun getText(): String = node.name
        override fun getTextRange(): com.intellij.openapi.util.TextRange =
            com.intellij.openapi.util.TextRange(node.startOffset, node.endOffset)
        override fun getName(): String = node.name
        override fun getNavigationElement(): PsiElement = this
        override fun canNavigate(): Boolean = true
        override fun canNavigateToSource(): Boolean = true
        override fun navigate(requestFocus: Boolean) {
            val project = file.project
            val vFile = file.virtualFile ?: return
            val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val editors = manager.openFile(vFile, requestFocus)
            val editor = editors.firstOrNull()
            if (editor is com.intellij.openapi.fileEditor.TextEditor) {
                editor.editor.caretModel.moveToOffset(node.startOffset)
                editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
            }
        }
        override fun getPresentableText(): String = node.name
    }
}
