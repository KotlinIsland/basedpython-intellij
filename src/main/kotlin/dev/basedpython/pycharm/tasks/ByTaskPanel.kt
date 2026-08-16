package dev.basedpython.pycharm.tasks

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
import com.intellij.util.ui.UIUtil
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
 * The "basedpython Tasks" tool window: what this repository's hook managers are configured to run,
 * and a way to run any of it without a terminal.
 *
 * Modelled on the platform's npm view, and on the same premise: a project's checks are already
 * written down somewhere, and the useful thing is to list them where they can be started and to say
 * how the last start went. Four tools are read for that — `.pre-commit-config.yaml` (pre-commit or
 * prek), `lefthook.yml` and its variants, and a `pyproject.toml` with a `[tool.pyprojectx]` — with
 * no process started to find out what exists.
 *
 * A row runs on double-click, which is npm's gesture rather than the test view's (where a
 * double-click opens the source). The difference is what the two views are *for*: a collected test
 * is somewhere to go, while a hook is something to run, and *Jump to Source* stays a click away in
 * the context menu.
 */
internal class ByTaskPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = ByTaskService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /** Per-node verdict, recomputed whenever the tree or the results change. */
    private var states: Map<ByTaskNode, ByTaskState> = emptyMap()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer({ states }, { allFiles() })
        // Lets the running spinner actually spin: a cell renderer paints once per repaint, so the
        // icon has to be allowed to drive repaints of its own row.
        UIUtil.putClientProperty(tree, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        TreeSpeedSearch.installOn(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = selectedNode() ?: return false
                // False on a grouping row, which leaves the double-click to the tree — where it
                // expands the row, the only thing it could usefully mean there.
                return ByTaskActions.run(project, node, DefaultRunExecutor.getRunExecutorInstance())
            }
        }.installOn(tree)

        PopupHandler.installPopupMenu(tree, popupActions(), POPUP_PLACE)

        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarActions(), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(JBScrollPane(tree))

        service.addListener(this) { render() }
        // A verdict changes what a row looks like, never which rows there are.
        service.addOutcomeListener(this) { recomputeStates() }
        render()
    }

    override fun dispose() = Unit

    private fun allFiles(): Boolean = service.allFiles

    private fun recomputeStates() {
        states = ByTaskStates.of(service.files, service.outcomes)
        tree.repaint()
    }

    // ---- rendering ---------------------------------------------------------

    /** Rebuilds the tree from the service's current state, keeping what the user had expanded. */
    private fun render() {
        val files = service.files
        renderEmptyText()

        val expanded = expandedPaths()
        val selected = selectedNode()?.key

        states = ByTaskStates.of(files, service.outcomes)
        root.removeAllChildren()
        files.forEach { root.add(build(it)) }
        model.reload()

        // A configuration of a dozen hooks is read at a glance and worth opening; a monorepo's is
        // not, and neither is a tree the user has already arranged.
        if (expanded.isEmpty()) {
            if (files.sumOf { it.taskCount } <= AUTO_EXPAND_LIMIT) TreeUtil.expandAll(tree) else expandRoot()
        } else {
            restoreExpanded(expanded)
        }
        selected?.let(::selectKey)
    }

    /**
     * The text shown when there is nothing to list.
     *
     * It names the files that were looked for. "No tasks" is otherwise an assertion the user has
     * every reason to disbelieve while looking at a `.pre-commit-config.yaml` in the project view —
     * and the usual causes (a `.yaml` in a subdirectory, a lefthook config named something else)
     * are diagnosed the moment the list of names is on screen.
     */
    private fun renderEmptyText() {
        val text = tree.emptyText
        text.clear()
        text.setText(BasedPythonBundle.message("tasks.empty"))
        text.appendLine(
            BasedPythonBundle.message("tasks.empty.files", ByTaskScan.FILES.joinToString(", ")),
            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
            null,
        )
        text.appendLine(
            BasedPythonBundle.message("tasks.action.refresh"),
            SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
        ) { service.refresh() }
    }

    private fun build(node: ByTaskNode): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node)
        node.children.forEach { swing.add(build(it)) }
        return swing
    }

    private class NodeRenderer(
        private val states: () -> Map<ByTaskNode, ByTaskState>,
        private val allFiles: () -> Boolean,
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
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ByTaskNode ?: return
            val state = states()[node] ?: ByTaskState.NOT_RUN
            icon = iconFor(node.kind, state)
            append(node.name)

            // A hook that only runs at another stage is not what a plain `pre-commit run` would
            // reach, and the row has to say so — the command it produces carries `--hook-stage`.
            if (node.kind == ByTaskKind.HOOK && node.stage != null) {
                append("  [${node.stage}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            // The one row whose Run does more than it says; see [LefthookTasks.scripts].
            if (node.kind == ByTaskKind.SCRIPT && node.stage != null) {
                append(
                    "  " + BasedPythonBundle.message("tasks.hint.script", node.stage),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                )
            }
            node.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            if (!node.kind.isTask) {
                node.taskCount.takeIf { it > 0 }?.let {
                    append(
                        "  " + BasedPythonBundle.message("tasks.count", it),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES,
                    )
                }
            }
            // The command this row runs, which is the question a hook that behaves differently in
            // the IDE than in a terminal always comes down to.
            toolTipText = ByTaskCommands.arguments(node, allFiles() && ByTaskCommands.supportsAllFiles(node.runner))
                ?.let { ByTaskCommands.describe(node.runner.binary, it) }
        }

        /** What a row *is*, until a run has said something about it. */
        private fun iconFor(kind: ByTaskKind, state: ByTaskState): Icon = when (state) {
            ByTaskState.PASSED -> AllIcons.RunConfigurations.TestPassed
            ByTaskState.FAILED -> AllIcons.RunConfigurations.TestFailed
            // The platform's spinner, which paints its own frames — but only in a renderer whose
            // tree opted in with ANIMATION_IN_RENDERER_ALLOWED.
            ByTaskState.RUNNING -> AnimatedIcon.Default.INSTANCE
            ByTaskState.NOT_RUN -> structureIcon(kind)
        }

        private fun structureIcon(kind: ByTaskKind): Icon = when (kind) {
            ByTaskKind.FILE -> BasedPythonIcons.Tasks
            ByTaskKind.SECTION -> AllIcons.Nodes.Folder
            else -> AllIcons.RunConfigurations.TestState.Run
        }
    }

    // ---- selection ---------------------------------------------------------

    private fun selectedNode(): ByTaskNode? =
        (TreeUtil.getSelectedPathIfOne(tree)?.lastPathComponent as? DefaultMutableTreeNode)
            ?.userObject as? ByTaskNode

    /** Re-selects the node with [key] after a rebuild, if it is still there. */
    private fun selectKey(key: String) {
        TreeUtil.treePathTraverser(tree).find { path ->
            (path.lastPathComponent as? DefaultMutableTreeNode)?.let {
                (it.userObject as? ByTaskNode)?.key
            } == key
        }?.let { TreeUtil.selectPath(tree, it, false) }
    }

    // ---- expansion ---------------------------------------------------------

    /**
     * The expanded rows, as the node names along each path.
     *
     * Names rather than the nodes themselves: a re-scan rebuilds every node, so identity is gone,
     * and a path of names still says "the user had `lefthook.yml` → `pre-commit` open".
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
        ((it as? DefaultMutableTreeNode)?.userObject as? ByTaskNode)?.name
    }

    // ---- actions -----------------------------------------------------------

    private fun toolbarActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(RunAction())
        add(RefreshAction())
        addSeparator()
        add(AllFilesAction())
        addSeparator()
        add(ExpandAllAction())
        add(CollapseAllAction())
    }

    private fun popupActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(RunAction())
        add(NavigateAction())
        addSeparator()
        add(AllFilesAction())
        add(RefreshAction())
    }

    /** The tool window's ⋮ menu, set by [ByTaskToolWindowFactory]. */
    fun gearActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(AllFilesAction())
    }

    private fun runnableSelection(): ByTaskNode? =
        selectedNode()?.takeIf { ByTaskCommands.arguments(it, allFiles = false) != null }

    private inner class RunAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("tasks.action.run"),
        BasedPythonBundle.messagePointer("tasks.action.run.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = runnableSelection() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = runnableSelection() ?: return
            ByTaskActions.run(project, node, DefaultRunExecutor.getRunExecutorInstance())
        }
    }

    private inner class RefreshAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("tasks.action.refresh"),
        BasedPythonBundle.messagePointer("tasks.action.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            service.refresh()
        }
    }

    /**
     * Whether runs from this view are asked for every file.
     *
     * Carries its text on the toolbar rather than an icon: it changes what every button next to it
     * does, and there is no icon in the platform's set that says "against all files rather than the
     * staged ones" without a tooltip nobody opens.
     */
    private inner class AllFilesAction : ToggleAction(
        BasedPythonBundle.messagePointer("tasks.action.allFiles"),
        BasedPythonBundle.messagePointer("tasks.action.allFiles.description"),
        null,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun isSelected(e: AnActionEvent): Boolean = service.allFiles

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            service.allFiles = selected
            // The tooltips say what will run, and what will run just changed.
            tree.repaint()
        }
    }

    private inner class NavigateAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("tasks.action.jumpToSource"),
        BasedPythonBundle.messagePointer("tasks.action.jumpToSource.description"),
        AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedNode() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val node = selectedNode() ?: return
            ByTaskActions.navigate(project, node)
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("tasks.action.expandAll"),
        BasedPythonBundle.messagePointer("tasks.action.expandAll"),
        AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
    }

    private inner class CollapseAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("tasks.action.collapseAll"),
        BasedPythonBundle.messagePointer("tasks.action.collapseAll"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
            expandRoot()
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "BasedPythonTasks"
        const val POPUP_PLACE = "BasedPythonTasksPopup"

        /** Tasks below which a fresh scan is shown fully expanded. */
        const val AUTO_EXPAND_LIMIT = 60
    }
}
