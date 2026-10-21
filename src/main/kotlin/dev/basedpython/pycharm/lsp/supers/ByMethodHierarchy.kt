package dev.basedpython.pycharm.lsp.supers

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyBrowserManager
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.MethodHierarchyBrowserBase
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiDocumentManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.ui.IconManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.navigation.ByDeclarationElement
import org.eclipse.lsp4j.SymbolKind
import java.text.MessageFormat
import java.util.Comparator
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Method Hierarchy (Ctrl+Shift+H) for basedpython: the classes that declare the member at the caret,
 * from the topmost one it overrides down to every one that overrides it — [ByOverrideHierarchy],
 * asked of `by`.
 *
 * Without this the action is greyed out in a `.by` file: the platform enables it only where a
 * provider is registered for the language. The target is the caret's place, as the platform's LSP
 * type hierarchy takes it, with nothing asked of `by` to decide it: the platform asks for a target
 * on the EDT, when the action runs, and which member the caret is in is `by`'s to say. The browser
 * asks, in the background, when it builds its tree.
 */
class ByMethodHierarchyProvider : HierarchyProvider {

    override fun getTarget(dataContext: DataContext): PsiElement? {
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? BasedPythonFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (byServerFor(project, virtualFile) == null) return null
        return ByDeclarationElement(file, editor.caretModel.offset, "", null, null)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        ByMethodHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as? ByMethodHierarchyBrowser)?.changeView(MethodHierarchyBrowserBase.getMethodType())
    }
}

/**
 * The Hierarchy tool window's method view, over [ByOverrideHierarchy]'s tree.
 *
 * The tree's root is a placeholder for the caret's place, hidden; its one child, the topmost
 * declaration, comes from `by` when the tree is first built. The tab's name and the selection follow
 * once `by` has said which member that is, since nothing names it before.
 */
internal class ByMethodHierarchyBrowser(project: Project, target: PsiElement) : MethodHierarchyBrowserBase(project, target) {

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    /** Sorting only: every class here declares the member, so there is nothing to hide, and `by` looks in the whole project. */
    override fun prependActions(actionGroup: DefaultActionGroup) {
        actionGroup.add(AlphaSortAction())
    }

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        val tree = createTree(false)
        BaseOnThisMethodAction()
            .registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_METHOD_HIERARCHY).shortcutSet, tree)
        tree.isRootVisible = false
        trees[getMethodType()] = tree
    }

    override fun createLegendPanel(): JPanel? = null

    override fun isApplicableElement(element: PsiElement): Boolean = element is ByDeclarationElement

    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? {
        if (type != getMethodType()) return null
        val target = psiElement as? ByDeclarationElement ?: return null
        val file = target.containingFile.virtualFile ?: return null
        val client = byServerFor(myProject, file) ?: return null
        return ByMethodHierarchyTreeStructure(myProject, client, target, ::shown)
    }

    override fun getComparator(): Comparator<NodeDescriptor<*>> {
        val state = HierarchyBrowserManager.getInstance(myProject).state
        return if (state != null && state.SORT_ALPHABETICALLY) AlphaComparator.getInstance() else compareBy { it.index }
    }

    /** Names the tab after the member `by` found, and selects it. On the EDT. */
    private fun shown(answer: ByOverrideHierarchyAnswer) {
        if (isDisposed) return
        val base = (answer as? ByOverrideHierarchyAnswer.Tree)?.base ?: return
        myContent?.displayName = MessageFormat.format(getMethodType(), base.declared.name)
        val tree = currentTree ?: return
        val visitor = TreeVisitor { path ->
            val node = ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ByMethodNodeDescriptor)?.node
            when {
                node == null -> TreeVisitor.Action.CONTINUE
                node === base -> TreeVisitor.Action.INTERRUPT
                else -> TreeVisitor.Action.CONTINUE
            }
        }
        TreeUtil.promiseSelect(tree, visitor)
        TreeUtil.promiseExpand(tree, visitor)
    }
}

/**
 * [ByOverrideHierarchy]'s tree, built in the background as the hierarchy's trees are: the hidden
 * root's children are where `by` is asked, once, and every node below has its children already.
 */
internal class ByMethodHierarchyTreeStructure(
    project: Project,
    private val client: LspClient,
    private val target: ByDeclarationElement,
    private val shown: (ByOverrideHierarchyAnswer) -> Unit,
) : HierarchyTreeStructure(project, ByMethodNodeDescriptor(project, null, target, isBase = true, node = null, message = null)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val parent = descriptor as? ByMethodNodeDescriptor ?: return emptyArray()
        // a message is a leaf; the one node with neither a declaration nor a message is the hidden root
        if (parent.message != null) return emptyArray()
        val node = parent.node ?: return answerFor(parent)
        return node.children.mapNotNull { child(parent, it) }.toTypedArray()
    }

    /** Asks `by`, and shows its answer under the hidden root. */
    private fun answerFor(root: ByMethodNodeDescriptor): Array<Any> {
        val file = target.containingFile.virtualFile ?: return emptyArray()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyArray()
        val answer = ByOverrideHierarchy.build(client, file, document, target.offset)
        ApplicationManager.getApplication().invokeLater({ shown(answer) }, ModalityState.nonModal(), myProject.disposed)
        return when (answer) {
            is ByOverrideHierarchyAnswer.Tree -> listOfNotNull(child(root, answer.root)).toTypedArray()
            is ByOverrideHierarchyAnswer.Nowhere ->
                arrayOf(ByMethodNodeDescriptor(myProject, root, target, isBase = false, node = null, message = answer.message))
        }
    }

    private fun child(parent: ByMethodNodeDescriptor, node: ByOverrideNode): ByMethodNodeDescriptor? {
        val declared = node.declared
        val element = ByDeclarationElement.at(client, declared.uri, declared.selectionRange.start, declared.name, declared.where, SymbolKind.Method)
            ?: return null
        return ByMethodNodeDescriptor(myProject, parent, element, node.isBase, node, message = null)
    }
}

/**
 * One class's declaration of the member: the class, marked as Java marks a class that defines the
 * method, and where it is. Or, with no [node], a [message] saying why there is no tree.
 */
/** `Class.member`, or the member alone where `by` does not say its class. */
internal val ByDeclared.name: String get() = container?.let { "$it.$member" } ?: member

internal class ByMethodNodeDescriptor(
    project: Project,
    parent: HierarchyNodeDescriptor?,
    element: ByDeclarationElement,
    isBase: Boolean,
    val node: ByOverrideNode?,
    val message: String?,
) : HierarchyNodeDescriptor(project, parent, element, isBase), Navigatable {

    override fun update(): Boolean {
        val changes = super.update()
        val old = myHighlightedText
        myHighlightedText = CompositeAppearance()
        when {
            node != null -> {
                val declared = node.declared
                myHighlightedText.ending.addText(declared.container ?: declared.member, getDefaultTextAttributes())
                declared.where?.let { myHighlightedText.ending.addText("  ($it)", getPackageNameAttributes()) }
                val classIcon = if (myIsBase) getBaseMarkerIcon(AllIcons.Nodes.Class) else AllIcons.Nodes.Class
                // an abstract declaration defines nothing, which Java's hierarchy shows by no mark
                icon = if (declared.abstract) classIcon else IconManager.getInstance().createRowIcon(AllIcons.Hierarchy.MethodDefined, classIcon)
            }
            message != null -> myHighlightedText.ending.addText(message, SimpleTextAttributes.GRAYED_ATTRIBUTES.toTextAttributes())
        }
        myName = myHighlightedText.text
        return changes || myHighlightedText != old
    }

    private fun getDefaultTextAttributes(): TextAttributes? =
        if (myIsBase) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES.toTextAttributes() else null

    override fun canNavigate(): Boolean = node != null && psiElement?.isValid == true

    override fun navigate(requestFocus: Boolean) {
        if (node != null) (psiElement as? ByDeclarationElement)?.navigate(requestFocus)
    }
}
