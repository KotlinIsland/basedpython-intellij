package dev.basedpython.pycharm.debug.recompose

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
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
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The "basedpython Recompositions" tool window: why every basedpython-ui scope ran, frame by frame.
 *
 * Thin on purpose. The rows are [ByRecompositionTree]'s and the records are
 * [ByRecompositionSession]'s; this puts them in a `Tree`, writes each row as a sentence, and wires
 * the four things the toolbar can do — watch, refresh, clear, and go to the code a row is about.
 *
 * Double-click opens what a row points at: the write site of a state cause, the definition of the
 * composable for a run. The call site of a run is the second action in the context menu.
 *
 * ## rendering
 *
 * The rows are recomputed whole — a pure function over a list bounded at
 * [ByRecompositionSession.HELD_LIMIT] — but the Swing tree is **reconciled**, not rebuilt: nodes
 * are matched to rows by key, level by level, and only a node whose row changed is touched. A
 * watch stream changes the newest frame and nothing else, so a notification every 100 ms costs
 * one frame's worth of nodes rather than every node in the tree, and what the user had expanded
 * and selected stays that way because the nodes it lives on are the same nodes.
 */
internal class ByRecompositionPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = ByRecompositionSession.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /** The key of the newest frame row as last rendered, so a newer one can inherit its expansion. */
    private var topFrameKey: String? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = RowRenderer()
        // Speed search: typing jumps to and highlights the rows whose sentence contains the text.
        // It does not narrow the tree — every frame stays where it is
        TreeSpeedSearch.installOn(tree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = selectedRow() ?: return false
                return ByRecompositionNavigation.open(project, row.target)
            }
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

    /** Brings the tree up to date with the service's records, touching only the nodes whose rows changed. */
    private fun render() {
        val records = service.records
        val rows = ByRecompositionTree.rows(records) + notes(records)
        renderEmptyText()

        val previousTop = topFrameKey
        val topWasExpanded = previousTop?.let { childWithKey(root, it) }?.let { tree.isExpanded(TreePath(it.path)) } == true
        val selected = selectedRow()?.key

        reconcile(root, rows)
        tree.expandPath(TreePath(root))

        // The newest frame is the one the question is about, so it opens itself: on the first
        // render, and again whenever a newer frame arrives while the previous newest was open.
        // A frame the user folded stays folded
        val top = rows.firstOrNull { it.kind == ByRowKind.FRAME }?.key
        if (top != null && (previousTop == null || (top != previousTop && topWasExpanded))) {
            childWithKey(root, top)?.let { tree.expandPath(TreePath(it.path)) }
        }
        topFrameKey = top
        if (selected != null && tree.selectionPath == null) select(selected)
    }

    /**
     * Makes [parent]'s children the nodes for [rows], in order, keeping every node whose key is
     * still there and recursing only into those whose row changed.
     *
     * Both lists keep their relative order — frames are sorted, rows within a frame are in record
     * order, and nothing reorders a record once held — so after the nodes for keys that have gone
     * are removed, a row either sits at its index already or is new there. A key found further
     * down is the rare case of a pull re-ordering a frame it replaced, and is rebuilt at its place.
     */
    private fun reconcile(parent: DefaultMutableTreeNode, rows: List<ByTreeRow>) {
        val wanted = rows.mapTo(HashSet(rows.size * 2)) { it.key }
        for (i in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            if (rowOf(child)?.key !in wanted) model.removeNodeFromParent(child)
        }
        for ((i, row) in rows.withIndex()) {
            val current = if (i < parent.childCount) parent.getChildAt(i) as DefaultMutableTreeNode else null
            val currentRow = current?.let(::rowOf)
            if (current != null && currentRow?.key == row.key) {
                if (currentRow != row) {
                    current.userObject = row
                    model.nodeChanged(current)
                    reconcile(current, row.children)
                }
                continue
            }
            childWithKey(parent, row.key, from = i + 1)?.let(model::removeNodeFromParent)
            model.insertNodeInto(build(row), parent, i)
        }
    }

    /**
     * What the tree says when it has no rows, which is a different sentence for each reason it
     * might have none: no session, a refusal from bpd, tracing off in the program, a pull that got
     * no answer, a program that has not stopped yet, a read on its way, and a trace with nothing
     * in it.
     */
    private fun renderEmptyText() {
        val text = tree.emptyText
        text.clear()
        when (val state = service.state) {
            ByRecompositionSession.State.NoSession ->
                text.setText(BasedPythonBundle.message("recompose.empty.noSession"))

            is ByRecompositionSession.State.Live -> text.setText(
                when {
                    state.refusal != null -> BasedPythonBundle.message("recompose.empty.refused", state.refusal)
                    !state.tracing -> BasedPythonBundle.message("recompose.empty.tracingOff")
                    state.unanswered != null -> BasedPythonBundle.message("recompose.empty.unanswered", state.unanswered)
                    !state.pulled && !state.paused -> BasedPythonBundle.message("recompose.empty.running")
                    !state.pulled -> BasedPythonBundle.message("recompose.empty.reading")
                    else -> BasedPythonBundle.message("recompose.empty.nothing")
                },
            )
        }
        if (!BasedPythonSettings.getInstance(project).debuggerRecompositions) {
            text.appendLine(
                BasedPythonBundle.message("recompose.empty.disabled"),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                null,
            )
        }
    }

    /**
     * What is missing or went unanswered, as rows at the bottom, so a tree never looks complete
     * when it is not: what fell off the ring and what the stream dropped, counted together; what
     * this window let go to stay bounded; what a newer bpd wrote that this build cannot read; and
     * a read that got no answer, when there are rows for the empty text not to be seen behind.
     */
    private fun notes(records: List<ByRecord>): List<ByTreeRow> {
        val live = service.state as? ByRecompositionSession.State.Live ?: return emptyList()
        val notes = ArrayList<ByTreeRow>()
        val missing = live.dropped + ByRecompositionTree.droppedInStream(records)
        if (missing > 0) {
            notes += note("note:dropped", BasedPythonBundle.message("recompose.note.dropped", missing))
        }
        if (live.letGo > 0) {
            notes += note("note:letGo", BasedPythonBundle.message("recompose.note.letGo", live.letGo, ByRecompositionSession.HELD_LIMIT))
        }
        if (live.unreadable > 0) {
            notes += note("note:unreadable", BasedPythonBundle.message("recompose.note.unreadable", live.unreadable))
        }
        if (live.unanswered != null && records.isNotEmpty()) {
            notes += note("note:unanswered", BasedPythonBundle.message("recompose.note.unanswered", live.unanswered))
        }
        return notes
    }

    private fun note(key: String, text: String) = ByTreeRow(ByRowKind.NOTE, key, text)

    private fun build(row: ByTreeRow): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(row)
        row.children.forEach { swing.add(build(it)) }
        return swing
    }

    private class RowRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ByTreeRow ?: return
            icon = iconFor(node)
            when (node.kind) {
                ByRowKind.FRAME -> append(node.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                ByRowKind.ERROR, ByRowKind.REFUSED -> append(node.text, SimpleTextAttributes.ERROR_ATTRIBUTES)
                ByRowKind.SKIPPED, ByRowKind.DISPOSED, ByRowKind.NOTE ->
                    append(node.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)

                ByRowKind.WRITE -> {
                    append("write ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(node.text)
                }

                ByRowKind.RUN, ByRowKind.CAUSE -> append(node.text)
            }
            node.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            toolTipText = tooltip(node)
        }

        /** Where the row leads, in full, with the generated place beside it — shown, never opened. */
        private fun tooltip(row: ByTreeRow): String? {
            val target = row.target ?: return null
            return buildString {
                append(target.file).append(':').append(target.line)
                target.generated?.let { append("  —  generated ").append(it.file).append(':').append(it.line) }
                target.reason?.let { append("  (").append(ByCauseSentences.unmapped(it)).append(')') }
                row.callSite?.let { append("\ncalled from ").append(it.file).append(':').append(it.line) }
            }
        }

        private fun iconFor(row: ByTreeRow): Icon = when (row.kind) {
            ByRowKind.FRAME -> AllIcons.Debugger.Frame
            ByRowKind.RUN -> AllIcons.Nodes.Function
            ByRowKind.CAUSE -> when (row.cause) {
                is ByCause.State, is ByCause.Derived -> AllIcons.Nodes.Variable
                is ByCause.Args -> AllIcons.Nodes.Parameter
                else -> AllIcons.General.Information
            }

            ByRowKind.SKIPPED -> AllIcons.Actions.Forward
            ByRowKind.DISPOSED -> AllIcons.Actions.Cancel
            ByRowKind.WRITE -> AllIcons.Actions.Edit
            ByRowKind.ERROR -> AllIcons.General.Error
            ByRowKind.REFUSED -> AllIcons.General.Warning
            ByRowKind.NOTE -> AllIcons.General.Information
        }
    }

    // ---- selection and nodes ----------------------------------------------

    private fun selectedRow(): ByTreeRow? =
        (TreeUtil.getSelectedPathIfOne(tree)?.lastPathComponent as? DefaultMutableTreeNode)
            ?.userObject as? ByTreeRow

    private fun rowOf(path: TreePath): ByTreeRow? = rowOf(path.lastPathComponent as? DefaultMutableTreeNode)

    private fun rowOf(node: DefaultMutableTreeNode?): ByTreeRow? = node?.userObject as? ByTreeRow

    /** The child of [parent] whose row has [key], looking from index [from] on, or null. */
    private fun childWithKey(parent: DefaultMutableTreeNode, key: String, from: Int = 0): DefaultMutableTreeNode? {
        for (i in from until parent.childCount) {
            val child = parent.getChildAt(i) as DefaultMutableTreeNode
            if (rowOf(child)?.key == key) return child
        }
        return null
    }

    private fun select(key: String) {
        TreeUtil.treePathTraverser(tree).find { rowOf(it)?.key == key }
            ?.let { TreeUtil.selectPath(tree, it, false) }
    }

    // ---- actions -----------------------------------------------------------

    private fun toolbarActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(WatchAction())
        add(RefreshAction())
        add(ClearAction())
        addSeparator()
        add(ExpandAllAction())
        add(CollapseAllAction())
    }

    private fun popupActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(JumpToSourceAction())
        add(JumpToCallSiteAction())
        addSeparator()
        add(RefreshAction())
        add(ClearAction())
    }

    /**
     * Whether bpd streams every record as it happens.
     *
     * A toggle rather than a button: it is a state of the session, and the toolbar has to show
     * which state it is in. It shows what bpd last confirmed, and the preference only until bpd
     * has said anything; the description says which, and why when bpd did not confirm — a watch
     * that was asked for and refused must not look on. Clicking while the preference is already
     * what is asked for sends it again.
     */
    private inner class WatchAction : ToggleAction(
        BasedPythonBundle.messagePointer("recompose.action.watch"),
        BasedPythonBundle.messagePointer("recompose.action.watch.description"),
        AllIcons.Debugger.Watch,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            (service.state as? ByRecompositionSession.State.Live)?.watching ?: service.watching

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            service.setWatching(state)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            val live = service.state as? ByRecompositionSession.State.Live
            e.presentation.description = when {
                live == null -> BasedPythonBundle.message("recompose.action.watch.description")
                live.watchProblem != null -> BasedPythonBundle.message("recompose.action.watch.problem", live.watchProblem)
                live.watching == true -> BasedPythonBundle.message("recompose.action.watch.on")
                live.watching == false -> BasedPythonBundle.message("recompose.action.watch.off")
                else -> BasedPythonBundle.message("recompose.action.watch.description")
            }
        }
    }

    /** Read the ring again. Only while the program is stopped: a running program answers nothing. */
    private inner class RefreshAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.refresh"),
        BasedPythonBundle.messagePointer("recompose.action.refresh.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (service.state as? ByRecompositionSession.State.Live)?.paused == true
        }

        override fun actionPerformed(e: AnActionEvent) = service.pull()
    }

    private inner class ClearAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.clear"),
        BasedPythonBundle.messagePointer("recompose.action.clear.description"),
        AllIcons.Actions.GC,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.isEmpty
        }

        override fun actionPerformed(e: AnActionEvent) = service.clear()
    }

    private inner class JumpToSourceAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.jumpToSource"),
        BasedPythonBundle.messagePointer("recompose.action.jumpToSource.description"),
        AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedRow()?.target != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            ByRecompositionNavigation.open(project, selectedRow()?.target)
        }
    }

    private inner class JumpToCallSiteAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.jumpToCallSite"),
        BasedPythonBundle.messagePointer("recompose.action.jumpToCallSite.description"),
        AllIcons.Actions.EditSource,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedRow()?.callSite != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            ByRecompositionNavigation.open(project, selectedRow()?.callSite)
        }
    }

    private inner class ExpandAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.expandAll"),
        BasedPythonBundle.messagePointer("recompose.action.expandAll"),
        AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
    }

    private inner class CollapseAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("recompose.action.collapseAll"),
        BasedPythonBundle.messagePointer("recompose.action.collapseAll"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
            tree.expandPath(TreePath(root))
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "BasedPythonRecompositions"
        const val POPUP_PLACE = "BasedPythonRecompositionsPopup"
    }
}
