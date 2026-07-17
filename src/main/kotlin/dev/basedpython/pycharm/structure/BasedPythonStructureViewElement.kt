package dev.basedpython.pycharm.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.NavigatablePsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.structure.IndentScanner.NodeKind
import dev.basedpython.pycharm.structure.IndentScanner.ScopeNode
import javax.swing.Icon

/**
 * A single node in the Structure View tree for a basedpython file.
 *
 * File-level element navigates to the file; all other elements navigate to the
 * start offset of the declaration within the editor.
 */
class BasedPythonStructureViewElement private constructor(
    private val file: BasedPythonFile,
    private val node: ScopeNode?,   // null for the file root
) : StructureViewTreeElement {

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    companion object {
        fun forFile(file: BasedPythonFile) = BasedPythonStructureViewElement(file, null)

        private fun forNode(file: BasedPythonFile, node: ScopeNode) =
            BasedPythonStructureViewElement(file, node)
    }

    // -----------------------------------------------------------------------
    // StructureViewTreeElement / NavigationItem
    // -----------------------------------------------------------------------

    override fun getValue(): Any = if (node == null) file else node

    override fun navigate(requestFocus: Boolean) {
        if (node == null) {
            (file as? NavigatablePsiElement)?.navigate(requestFocus)
            return
        }
        val offset = node.startOffset
        val project = file.project
        val vFile = file.virtualFile ?: return
        val manager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val editors = manager.openFile(vFile, requestFocus)
        val editor = editors.firstOrNull()
        if (editor is com.intellij.openapi.fileEditor.TextEditor) {
            editor.editor.caretModel.moveToOffset(offset)
            editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = node?.name ?: file.name
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon? = iconForKind(node?.kind)
    }

    override fun getChildren(): Array<TreeElement> {
        if (node == null) {
            // root: build tree from file text
            val text = file.text ?: return emptyArray()
            val roots = IndentScanner.buildTree(text)
            return roots.map { forNode(file, it) }.toTypedArray()
        }
        return node.children.map { forNode(file, it) }.toTypedArray()
    }

    // -----------------------------------------------------------------------
    // Icon mapping
    // -----------------------------------------------------------------------

    private fun iconForKind(kind: NodeKind?): Icon? = when (kind) {
        NodeKind.CLASS -> AllIcons.Nodes.Class
        NodeKind.FUNCTION -> AllIcons.Nodes.Method
        NodeKind.FIELD -> AllIcons.Nodes.Field
        NodeKind.IMPORT_BLOCK -> AllIcons.Nodes.Include
        NodeKind.REGION -> AllIcons.Nodes.Folder
        null -> AllIcons.FileTypes.Unknown
    }
}
