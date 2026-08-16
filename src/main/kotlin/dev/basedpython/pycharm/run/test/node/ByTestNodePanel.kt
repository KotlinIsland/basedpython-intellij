package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.AnimatedIcon
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
 * A *collected* tree first: what tests exist, available before anything has run and without running
 * anything. It then carries the outcome of whatever has run since — from any run, since
 * [ByTestRunStateListener] listens to the SM runner rather than to this view's own buttons — so the
 * question "did that pass" is answered where the tests are listed, not only in the run window.
 */
internal class ByTestNodePanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = ByTestNodeService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /** Per-node outcome, recomputed whenever the tree or the results change. */
    private var states: Map<ByTestNode, ByTestState> = emptyMap()

    /** The tree as collected, before filtering; what [states] are computed against. */
    private var collectedTree: ByTestNode? = null

    /**
     * Which outcomes to show; all of them until the user says otherwise.
     *
     * Declared above `init` on purpose: `init` renders, rendering filters, and a Kotlin property
     * declared below an init block is still null while that block runs.
     */
    private val visibleStates: MutableSet<ByTestState> = ByTestFilter.ALL.toMutableSet()

    init {
        // Visibility is decided per render: a root row is always a row, and a tree that has one can
        // never show its empty text. See [render].
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer { states }
        // Lets the running spinner actually spin: a cell renderer paints once per repaint, so the
        // icon has to be allowed to drive repaints of its own row.
        com.intellij.util.ui.UIUtil.putClientProperty(
            tree, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true,
        )
        TreeSpeedSearch.installOn(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = selectedNode() ?: return false
                return ByTestNodeActions.navigate(project, node.target, node.source)
            }
        }.installOn(tree)

        PopupHandler.installPopupMenu(tree, popupActions(), POPUP_PLACE)

        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarActions(), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(JBScrollPane(tree))

        service.addListener(this) { render() }
        // An outcome changes what a row looks like, never which rows there are, so it repaints
        // rather than rebuilding — a run reports one of these per test, and rebuilding the tree
        // that often would collapse it under the user mid-run.
        service.addOutcomeListener(this) { outcomesChanged() }
        render()
    }

    override fun dispose() = Unit

    /**
     * Takes in a change of results.
     *
     * A repaint is enough while everything is shown — the rows are the same rows, only their icons
     * moved. With a filter on, an outcome can move a row *out* of the tree, so that needs the
     * rebuild [render] does.
     */
    private fun outcomesChanged() {
        if (visibleStates.containsAll(ByTestFilter.ALL)) recomputeStates() else render()
    }

    private fun recomputeStates() {
        states = collectedTree?.let { ByTestStates.of(it, service.outcomes) } ?: emptyMap()
        tree.repaint()
    }


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

        // States belong to the collected tree: computing them after filtering would ask a pruned
        // parent what its remaining children did, which is not what its icon should say.
        collectedTree = source
        states = source?.let { ByTestStates.of(it, service.outcomes) } ?: emptyMap()
        val shown = source?.let { ByTestFilter.apply(it, states, visibleStates) }

        root.userObject = shown
        root.removeAllChildren()
        shown?.children?.forEach { root.add(build(it)) }
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
        // "No tests" and "no tests you asked to see" are different answers, and offering Refresh
        // for the second would send the user chasing a collection that is working fine.
        val filteredOut = collected && (collectedTree?.testCount ?: 0) > 0
        if (filteredOut) {
            text.setText(BasedPythonBundle.message("testNodes.empty.filtered"))
            text.appendLine(
                BasedPythonBundle.message("testNodes.action.filter.showAll"),
                SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
            ) {
                visibleStates.addAll(ByTestFilter.ALL)
                render()
            }
            return
        }
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

    /**
     * Runs the selected node, or — with nothing selected — everything, which is one launch per kind
     * of test in the tree (see [ByTestNodeActions.runAll]).
     */
    private fun launch(executor: com.intellij.execution.Executor) {
        val node = selectedNode()
        if (node == null || node.target == null) {
            ByTestNodeActions.runAll(project, executor, sourcesInTree())
        } else {
            ByTestNodeActions.run(project, node.target, executor, node.source)
        }
    }

    /** Which kinds of test the current tree holds; empty before anything is collected. */
    private fun sourcesInTree(): Set<ByTestSource> {
        val root = root.userObject as? ByTestNode ?: return emptySet()
        val sources = LinkedHashSet<ByTestSource>()
        fun walk(node: ByTestNode) {
            if (node.kind == ByTestNodeKind.FILE) sources += node.source
            node.children.forEach(::walk)
        }
        walk(root)
        return sources
    }

    private fun build(node: ByTestNode): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node)
        node.children.forEach { swing.add(build(it)) }
        return swing
    }

    private class NodeRenderer(
        private val states: () -> Map<ByTestNode, ByTestState>,
    ) : ColoredTreeCellRenderer() {
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
            val state = states()[node] ?: ByTestState.NOT_RUN
            icon = iconFor(node.kind, state)
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

        /**
         * The icon for a node: what it *is*, until a run has said something about it.
         *
         * A test carries its own outcome; a file, class or directory carries the worst of what is
         * under it, so a collapsed tree still points at the failure. Containers keep their own icon
         * while nothing has run, since a folder of never-run tests is a folder, not a result.
         */
        private fun iconFor(kind: ByTestNodeKind, state: ByTestState): Icon {
            if (state != ByTestState.NOT_RUN) {
                outcomeIcon(state)?.let { return it }
            }
            return structureIcon(kind)
        }

        private fun outcomeIcon(state: ByTestState): Icon? = when (state) {
            ByTestState.PASSED -> AllIcons.RunConfigurations.TestPassed
            ByTestState.FAILED -> AllIcons.RunConfigurations.TestFailed
            ByTestState.SKIPPED -> AllIcons.RunConfigurations.TestSkipped
            // The platform's spinner, which paints its own frames — but only in a renderer whose
            // tree opted in with ANIMATION_IN_RENDERER_ALLOWED; without that flag a renderer is
            // assumed to be a stamp and the icon never advances.
            ByTestState.RUNNING -> AnimatedIcon.Default.INSTANCE
            ByTestState.NOT_RUN -> null
        }

        private fun structureIcon(kind: ByTestNodeKind): Icon = when (kind) {
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
        add(filterActions())
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
     * The funnel: one toggle per outcome, plus a way back to all of them.
     *
     * A popup rather than five toolbar buttons, because the answer is "all" almost always and a row
     * of five permanently-lit toggles would spend the toolbar on a question nobody is asking. The
     * icon says whether anything is filtered, which is the part that must never be missed — a view
     * that quietly hides tests is worse than no view.
     */
    private fun filterActions(): DefaultActionGroup {
        val group = object : DefaultActionGroup(
            BasedPythonBundle.messagePointer("testNodes.action.filter"),
            true,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                val hidden = ByTestFilter.ALL - visibleStates
                e.presentation.icon = AllIcons.General.Filter
                // A filtered view must say so on the toolbar, not only inside a popup nobody has
                // open: a tree quietly missing tests is worse than no tree. There is no
                // "filter is on" icon in the platform set, so the button grows a label instead.
                e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, hidden.isNotEmpty())
                e.presentation.text = if (hidden.isEmpty()) {
                    BasedPythonBundle.message("testNodes.action.filter")
                } else {
                    BasedPythonBundle.message("testNodes.action.filter.on", hidden.size)
                }
                e.presentation.description = hidden
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { BasedPythonBundle.message("testNodes.state.${it.name.lowercase()}") }
                    ?.let { BasedPythonBundle.message("testNodes.action.filter.hiding", it) }
                    ?: BasedPythonBundle.message("testNodes.action.filter.description")
            }
        }
        group.templatePresentation.icon = AllIcons.General.Filter
        for (state in ByTestState.entries) group.add(StateFilterAction(state))
        group.addSeparator()
        group.add(ShowAllAction())
        return group
    }

    /** One outcome's visibility. */
    private inner class StateFilterAction(private val state: ByTestState) : ToggleAction(
        BasedPythonBundle.messagePointer("testNodes.state.${state.name.lowercase()}"),
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = state in visibleStates

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            if (selected) visibleStates += state else visibleStates -= state
            render()
        }
    }

    private inner class ShowAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("testNodes.action.filter.showAll"),
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !visibleStates.containsAll(ByTestFilter.ALL)
        }

        override fun actionPerformed(e: AnActionEvent) {
            visibleStates.addAll(ByTestFilter.ALL)
            render()
        }
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

        override fun actionPerformed(e: AnActionEvent) {
            service.refresh()
        }
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

        override fun actionPerformed(e: AnActionEvent) = launch(DefaultRunExecutor.getRunExecutorInstance())
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

        override fun actionPerformed(e: AnActionEvent) = launch(DefaultDebugExecutor.getDebugExecutorInstance())
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
            val node = selectedNode() ?: return
            ByTestNodeActions.navigate(project, node.target, node.source)
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
