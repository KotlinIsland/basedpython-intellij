package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The "basedpython Tests" tool window: the test tree as `--collect-only` reports it, with the
 * actions that make it useful — run, debug, and jump to the source.
 *
 * This is a *collected* tree, not a results tree: it is what tests exist, available before anything
 * has run and without running anything. Outcomes stay where the platform already shows them, in the
 * run window's test tree, which the same configuration feeds.
 */
internal class ByTestNodePanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = ByTestNodeService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    init {
        // Visibility is decided per render: a root row is always a row, and a tree that has one can
        // never show its empty text. See [render].
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        TreeSpeedSearch.installOn(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean =
                ByTestNodeActions.navigate(project, selectedNode()?.target)
        }.installOn(tree)

        PopupHandler.installPopupMenu(tree, popupActions(), POPUP_PLACE)

        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarActions(), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(JBScrollPane(tree))

        service.addListener(this) { render() }
        render()
    }

    override fun dispose() = Unit

    // ---- rendering ---------------------------------------------------------

    /** Rebuilds the tree from the service's current state, keeping what the user had expanded. */
    private fun render() {
        val state = service.state
        tree.setPaintBusy(state is ByTestNodeService.State.Collecting)
        val source = when (state) {
            is ByTestNodeService.State.Collected -> state.tree
            is ByTestNodeService.State.Collecting -> state.tree
            ByTestNodeService.State.Idle -> null
        }
        renderEmptyText(state)

        val expanded = expandedPaths()
        val selected = selectedNode()?.target

        root.userObject = source
        root.removeAllChildren()
        source?.children?.forEach { root.add(build(it)) }
        model.reload()
        // The root carries the total, which is worth a row — but only once there is something under
        // it, since a visible root is a row and a tree with a row shows no empty text.
        tree.isRootVisible = root.childCount > 0

        // A tree nobody has opened yet is more useful open: a collection of a handful of files is
        // read at a glance, while expanding hundreds of them is neither wanted nor cheap.
        if (expanded.isEmpty()) {
            if ((source?.testCount ?: 0) <= AUTO_EXPAND_LIMIT) TreeUtil.expandAll(tree) else expandRoot()
        } else {
            restoreExpanded(expanded)
        }
        selected?.let(::selectTarget)
    }

    /**
     * The text shown when the tree has no rows.
     *
     * A collection that ran and found nothing is the case worth spelling out: "no tests" is a
     * conclusion the user has every right to disbelieve, since they can run `pytest --collect-only`
     * themselves and see some. So that state offers the output that produced it, which carries the
     * command, its working directory and pytest's own rootdir line.
     */
    private fun renderEmptyText(state: ByTestNodeService.State) {
        val text = tree.emptyText
        text.clear()
        if (state is ByTestNodeService.State.Collecting) {
            text.setText(BasedPythonBundle.message("testNodes.collecting"))
            return
        }
        val collected = state is ByTestNodeService.State.Collected
        text.setText(
            BasedPythonBundle.message(if (collected) "testNodes.empty.collected" else "testNodes.empty"),
        )
        if (collected) {
            text.appendLine(
                BasedPythonBundle.message("testNodes.action.viewOutput"),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) { ByShowCollectionOutputAction.show(project) }
        }
        text.appendLine(
            BasedPythonBundle.message("testNodes.empty.collect"),
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) { service.refresh() }
    }

    private fun build(node: ByTestNode): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node)
        node.children.forEach { swing.add(build(it)) }
        return swing
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ByTestNode ?: return
            icon = iconFor(node.kind)
            append(node.name)
            node.detail?.let {
                append("  $it", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
            // A count on a leaf test would only ever read "1"; on everything else it is the answer
            // to "how much does running this node cost".
            if (node.kind != ByTestNodeKind.TEST && node.kind != ByTestNodeKind.CASE) {
                node.testCount.takeIf { it > 0 }?.let {
                    append(
                        "  " + BasedPythonBundle.message("testNodes.count", it),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
        }

        private fun iconFor(kind: ByTestNodeKind): Icon = when (kind) {
            ByTestNodeKind.ROOT -> AllIcons.Nodes.TestSourceFolder
            ByTestNodeKind.DIRECTORY -> AllIcons.Nodes.Folder
            ByTestNodeKind.FILE -> BasedPythonIcons.Logo
            ByTestNodeKind.CLASS -> AllIcons.Nodes.Class
            // "Not ran" rather than "unknown": these are tests whose outcome nothing has asked for
            // yet, which is exactly what the icon means in the platform's own test trees.
            ByTestNodeKind.TEST, ByTestNodeKind.CASE -> AllIcons.RunConfigurations.TestNotRan
            ByTestNodeKind.ERROR -> AllIcons.General.Error
        }
    }

    // ---- selection ---------------------------------------------------------

    private fun selectedNode(): ByTestNode? =
        (TreeUtil.getSelectedPathIfOne(tree)?.lastPathComponent as? DefaultMutableTreeNode)
            ?.userObject as? ByTestNode

    /** Re-selects the node with [target] after a rebuild, if it is still there. */
    private fun selectTarget(target: String) {
        TreeUtil.treePathTraverser(tree).find { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)?.let {
                (it.userObject as? ByTestNode)?.target
            } == target
        }?.let { TreeUtil.selectPath(tree, it, false) }
    }

    // ---- expansion ---------------------------------------------------------

    /**
     * The expanded rows, as the node names along each path.
     *
     * Names rather than the nodes themselves: a refresh rebuilds every node, so identity is gone,
     * and a path of names still says "the user had `tests/unit` → `test_math.by` open" for as long
     * as those exist.
     */
    private fun expandedPaths(): Set<List<String>> {
        val paths = LinkedHashSet<List<String>>()
        val enumeration = tree.getExpandedDescendants(TreePath(root)) ?: return paths
        for (path in enumeration) paths += names(path)
        return paths
    }

    private fun restoreExpanded(paths: Set<List<String>>) {
        expandRoot()
        var row = 0
        // Row-by-row rather than by path: expanding a row reveals its children, which then get
        // their own turn as the row count grows.
        while (row < tree.rowCount) {
            val path = tree.getPathForRow(row)
            if (path != null && names(path) in paths) tree.expandRow(row)
            row++
        }
    }

    private fun expandRoot() {
        tree.expandPath(TreePath(root))
    }

    private fun names(path: TreePath): List<String> = path.path.mapNotNull {
        ((it as? DefaultMutableTreeNode)?.userObject as? ByTestNode)?.name
    }

    // ---- actions -----------------------------------------------------------

    private fun toolbarActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(RefreshAction())
        addSeparator()
        add(RunAction())
        add(DebugAction())
        addSeparator()
        add(ExpandAllAction())
        add(CollapseAllAction())
    }

    private fun popupActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(RunAction())
        add(DebugAction())
        addSeparator()
        add(NavigateAction())
        addSeparator()
        add(RefreshAction())
        add(ByShowCollectionOutputAction(project))
    }

    /**
     * The tool window's ⋮ menu, set by [ByTestNodeToolWindowFactory].
     *
     * *View Collection Output* lives here rather than on the toolbar because it is a diagnostic:
     * wanted the once, when the tree disagrees with the user's own `pytest --collect-only`, and
     * clutter every other time.
     */
    fun gearActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(ByShowCollectionOutputAction(project))
    }

    private inner class RefreshAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.refresh"),
        BasedPythonBundle.messagePointer("testNodes.action.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.state !is ByTestNodeService.State.Collecting
        }

        override fun actionPerformed(e: AnActionEvent) = service.refresh()
    }

    /** Runs the selected node, or — with nothing selected — every test in the project. */
    private inner class RunAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.run"),
        BasedPythonBundle.messagePointer("testNodes.action.run.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.kind != ByTestNodeKind.ERROR
        }

        override fun actionPerformed(e: AnActionEvent) {
            ByTestNodeActions.run(project, selectedNode()?.target, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    private inner class DebugAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.debug"),
        BasedPythonBundle.messagePointer("testNodes.action.debug.description"),
        AllIcons.Actions.StartDebugger,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.kind != ByTestNodeKind.ERROR
        }

        override fun actionPerformed(e: AnActionEvent) {
            ByTestNodeActions.run(project, selectedNode()?.target, DefaultDebugExecutor.getDebugExecutorInstance())
        }
    }

    private inner class NavigateAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.jumpToSource"),
        BasedPythonBundle.messagePointer("testNodes.action.jumpToSource.description"),
        AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode()?.target != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            ByTestNodeActions.navigate(project, selectedNode()?.target)
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.expandAll"),
        BasedPythonBundle.messagePointer("testNodes.action.expandAll"),
        AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
    }

    private inner class CollapseAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.collapseAll"),
        BasedPythonBundle.messagePointer("testNodes.action.collapseAll"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
            expandRoot()
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "BasedPythonTestNodes"
        const val POPUP_PLACE = "BasedPythonTestNodesPopup"

        /** Tests below which a fresh collection is shown fully expanded. */
        const val AUTO_EXPAND_LIMIT = 200
    }
}
