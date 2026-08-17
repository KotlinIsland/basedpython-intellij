package dev.basedpython.pycharm.env.manager

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The "basedpython Environment" tool window: which environment this project runs in, what is in it,
 * and the one thing to press when something is wrong.
 *
 * ### What it is for
 *
 * PyCharm's interpreter UI answers "which interpreter is selected", which is the question an IDE
 * cares about. It is not the question a person has when their imports are red. That one is "is there
 * an environment, does it have what the project says it needs, and what do I press" — and it is
 * answered here in a banner with a button, above the dependency tree that proves the answer.
 *
 * ### Why a tree, and why grouped
 *
 * A flat package list answers "what is installed" and nothing else. It cannot say which of forty
 * rows the project actually asked for and which came along for the ride, and it cannot say whether
 * a package is a real dependency or a test-only one. Both are answerable — the resolver knows —
 * and both change what the user does next: you remove a declared dependency, you do not remove a
 * transitive one, and a `dev` group entry is not shipped to anyone.
 *
 * So the top level is *where a requirement is declared* — the main list, extras, named groups — the
 * second level is the requirements themselves, and everything below that is what they pulled in.
 * That structure also makes the destructive action safe: *Remove* is offered only on a declared
 * requirement, and it knows which group to remove it from, instead of guessing.
 *
 * The window deliberately owns no state. Everything on screen is a render of [EnvService.status],
 * so there is no way for the view to disagree with what the next command will actually see.
 */
internal class EnvPanel(private val project: Project) :
    SimpleToolWindowPanel(true, true), Disposable {

    private val service = EnvService.getInstance(project)

    private val header = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private val summary = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model)

    /**
     * Kept so a state change can update it at once.
     *
     * A toolbar otherwise re-evaluates its actions on its own schedule, which for a sync that takes
     * a minute means the buttons it should have disabled stay live for the first second of it.
     */
    private val toolbar: ActionToolbar

    /** Installed version by package name, for the row that says the tree and the machine disagree. */
    private var installed: Map<String, EnvPackage> = emptyMap()

    /** What each package is doing right now, so a row can spin while it installs. */
    private var progress: EnvProgress = EnvProgress()

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.cellRenderer = NodeRenderer({ installed }, { progress })
        // A cell renderer paints once per repaint, so the spinner has to be allowed to drive
        // repaints of its own row — without this the icon is drawn as a single frozen frame.
        UIUtil.putClientProperty(tree, AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        TreeSpeedSearch.installOn(tree)
        PopupHandler.installPopupMenu(tree, popupActions(), POPUP_PLACE)

        toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarActions(), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)

        val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        setContent(content)

        service.addListener(this) { render() }
        render()
        service.refreshIfNeeded()
    }

    override fun dispose() = Unit

    // ---- rendering ---------------------------------------------------------

    private fun render() {
        val status = service.status
        installed = status.packages.associateBy { it.name.lowercase() }
        progress = service.progress

        header.removeAll()
        renderBanner(status)?.let(header::add)
        summary.text = describe(status)
        header.add(summary)
        header.revalidate()
        header.repaint()

        renderTree(status)
        toolbar.updateActionsAsync()
    }

    /**
     * Rebuilds the tree, keeping what the user had expanded.
     *
     * Expansion is restored by group name rather than by node identity, because a refresh rebuilds
     * every node — and the thing worth preserving is "the user had `dev` open", which survives.
     * Deeper expansion is not restored: a transitive subtree that the user opened to answer one
     * question is not something they are still looking at three syncs later.
     */
    private fun renderTree(status: EnvStatus) {
        val expandedGroups = expandedGroupLabels()
        root.removeAllChildren()
        EnvTreeRows.build(status).forEach { root.add(swing(it)) }
        model.reload()

        when {
            // The flat fallback is one level deep; there is nothing to expand.
            EnvTreeRows.isFlat(status) -> Unit
            // First look: open the group a person came here for, and only that one. Opening all of
            // them puts a project's entire transitive closure on screen at once.
            expandedGroups.isEmpty() -> expandDefaultGroup()
            else -> restoreExpanded(expandedGroups)
        }
    }

    private fun swing(node: EnvRowNode): DefaultMutableTreeNode {
        val swing = DefaultMutableTreeNode(node.row)
        node.children.forEach { swing.add(swing(it)) }
        return swing
    }

    /** Opens the main dependency list, or the first group when a project declares none. */
    private fun expandDefaultGroup() {
        tree.expandPath(TreePath(root))
        val preferred = (0 until root.childCount)
            .map { root.getChildAt(it) as DefaultMutableTreeNode }
            .firstOrNull { (it.userObject as? EnvRow.Group)?.group?.target == EnvDependencyTarget.Main }
            ?: root.firstChild as? DefaultMutableTreeNode
            ?: return
        tree.expandPath(TreePath(arrayOf(root, preferred)))
    }

    private fun expandedGroupLabels(): Set<String> {
        val paths = tree.getExpandedDescendants(TreePath(root)) ?: return emptySet()
        return paths.toList()
            .mapNotNull { ((it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? EnvRow.Group) }
            .mapTo(LinkedHashSet()) { it.group.target.label }
    }

    private fun restoreExpanded(labels: Set<String>) {
        tree.expandPath(TreePath(root))
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as DefaultMutableTreeNode
            val label = (child.userObject as? EnvRow.Group)?.group?.target?.label ?: continue
            if (label in labels) tree.expandPath(TreePath(arrayOf(root, child)))
        }
    }

    /**
     * The banner, or null when there is nothing to say.
     *
     * A healthy project gets no banner at all — not a green one. A row that is always present is a
     * row nobody reads, and this is the only place the window can raise its voice.
     */
    private fun renderBanner(status: EnvStatus): JComponent? {
        if (!status.health.isActionable) {
            return status.error?.let { error ->
                EditorNotificationPanel(EditorNotificationPanel.Status.Error).apply {
                    text = BasedPythonBundle.message("env.banner.error", error)
                    createActionLabel(BasedPythonBundle.message("env.action.reread")) { service.refresh() }
                }
            }
        }

        val backend = status.backend ?: return null
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Warning)
        when (status.health) {
            EnvHealth.TOOL_MISSING -> {
                panel.text = BasedPythonBundle.message("env.banner.toolMissing", backend.displayName)
                if (backend.installer != null) {
                    panel.createActionLabel(
                        BasedPythonBundle.message("env.action.installTool", backend.displayName),
                    ) { EnvOperations.installTool(project) }
                }
            }

            EnvHealth.NO_ENVIRONMENT -> {
                panel.text = BasedPythonBundle.message("env.banner.noEnvironment", backend.displayName)
                panel.createActionLabel(BasedPythonBundle.message("env.action.createEnvironment")) {
                    EnvOperations.createEnvironment(project, null)
                }
                panel.createActionLabel(BasedPythonBundle.message("env.action.chooseInterpreter")) {
                    EnvPythonPicker.choose(project, tree)
                }
            }

            EnvHealth.OUT_OF_SYNC -> {
                panel.text = BasedPythonBundle.message("env.banner.outOfSync")
                panel.createActionLabel(BasedPythonBundle.message("env.action.sync")) {
                    EnvOperations.sync(project)
                }
            }

            else -> return null
        }
        return panel
    }

    /** The one-line description of the environment, always shown once something has looked. */
    private fun describe(status: EnvStatus): String {
        // While something is installing, the header says what — a package being fetched is the most
        // useful thing the window can be saying, and it is more specific than "busy".
        service.progress.headline?.takeIf { service.busy }?.let {
            return BasedPythonBundle.message("env.summary.working", it)
        }
        if (!service.scanned) return BasedPythonBundle.message("env.summary.scanning")
        val backend = status.backend ?: return BasedPythonBundle.message("env.summary.unmanaged")
        val env = status.environment
            ?: return BasedPythonBundle.message(
                "env.summary.wouldBe",
                backend.displayName,
                status.environmentRoot?.toString().orEmpty(),
            )
        return BasedPythonBundle.message(
            "env.summary.ready",
            backend.displayName,
            env.pythonVersion ?: "?",
            env.root.toString(),
        )
    }

    // ---- rows --------------------------------------------------------------

    private class NodeRenderer(
        private val installed: () -> Map<String, EnvPackage>,
        private val progress: () -> EnvProgress,
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
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is EnvRow.Group -> renderGroup(item)
                is EnvRow.Package -> renderPackage(item)
                is EnvRow.Flat -> renderFlat(item)
                else -> Unit
            }
        }

        private fun renderGroup(row: EnvRow.Group) {
            icon = groupIcon(row.group.target)
            append(row.group.target.label)
            append(
                "  " + BasedPythonBundle.message("env.tree.count", row.group.packageCount()),
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            toolTipText = BasedPythonBundle.message(targetTooltipKey(row.group.target))
        }

        /**
         * A declared requirement is drawn as ordinary text and a transitive one greyed, so the two
         * levels the user acts on differently look different without needing a legend.
         */
        private fun renderPackage(row: EnvRow.Package) {
            val node = row.node
            val here = installed()[node.name.lowercase()]
            val activity = progress().activityOf(node.name)
            // The platform's spinner, which paints its own frames — but only in a tree that opted in
            // with ANIMATION_IN_RENDERER_ALLOWED.
            icon = when {
                activity != null -> AnimatedIcon.Default.INSTANCE
                row.declared -> AllIcons.Nodes.PpLib
                else -> AllIcons.Nodes.PpLibFolder
            }

            val nameAttributes = when {
                here == null -> SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES
                row.declared -> SimpleTextAttributes.REGULAR_ATTRIBUTES
                else -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            }
            append(node.name, nameAttributes)
            append("  ${node.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

            when {
                // Resolved to one version, a different one on disk. This is what drift looks like
                // when you point at it, and it is the row the sync banner is talking about.
                here != null && here.version.isNotEmpty() && here.version != node.version ->
                    append(
                        "  " + BasedPythonBundle.message("env.tree.installedVersion", here.version),
                        SimpleTextAttributes.ERROR_ATTRIBUTES,
                    )
                // Not on disk. Ordinary for an extra or a non-default group, which is why it is
                // stated quietly rather than coloured as a problem.
                here == null ->
                    append(
                        "  " + BasedPythonBundle.message("env.tree.notInstalled"),
                        SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                    )
            }

            if (activity != null) {
                append(
                    "  " + BasedPythonBundle.message(activityKey(activity)),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                )
            }
            if (node.expandedElsewhere) {
                append(
                    "  " + BasedPythonBundle.message("env.tree.shownAbove"),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES,
                )
            }
            toolTipText = if (row.declared) {
                BasedPythonBundle.message("env.tree.tooltip.declared")
            } else {
                BasedPythonBundle.message("env.tree.tooltip.transitive")
            }
        }

        private fun renderFlat(row: EnvRow.Flat) {
            val activity = progress().activityOf(row.pkg.name)
            icon = if (activity != null) AnimatedIcon.Default.INSTANCE else AllIcons.Nodes.PpLib
            append(row.pkg.name)
            append("  ${row.pkg.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            row.pkg.editableLocation?.let {
                append(
                    "  " + BasedPythonBundle.message("env.package.editable", it),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES,
                )
            }
        }

        private fun activityKey(activity: EnvPackageActivity): String = when (activity) {
            EnvPackageActivity.DOWNLOADING -> "env.activity.downloading"
            EnvPackageActivity.PREPARING -> "env.activity.preparing"
            EnvPackageActivity.REMOVING -> "env.activity.removing"
        }

        private fun groupIcon(target: EnvDependencyTarget): Icon = when (target) {
            EnvDependencyTarget.Main -> AllIcons.Nodes.PpLibFolder
            is EnvDependencyTarget.Extra -> AllIcons.Nodes.Package
            is EnvDependencyTarget.Group -> AllIcons.Nodes.ConfigFolder
        }

        private fun targetTooltipKey(target: EnvDependencyTarget): String = when (target) {
            EnvDependencyTarget.Main -> "env.tree.tooltip.main"
            is EnvDependencyTarget.Extra -> "env.tree.tooltip.extra"
            is EnvDependencyTarget.Group -> "env.tree.tooltip.group"
        }
    }

    // ---- selection ---------------------------------------------------------

    /**
     * What is selected, each row paired with the group heading above it.
     *
     * Walking up the path is the only part of the selection rules that needs a tree; what those
     * pairs *mean* lives in [EnvTreeRows], where it can be tested.
     */
    private fun selection(): List<EnvTreeRows.Selected> =
        tree.selectionPaths.orEmpty().mapNotNull { path ->
            val row = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? EnvRow
                ?: return@mapNotNull null
            EnvTreeRows.Selected(row, groupOf(path))
        }

    /** The group heading above [path], whatever depth the selection is at. */
    private fun groupOf(path: TreePath): EnvDependencyGroup? =
        path.path.asList().asReversed().firstNotNullOfOrNull {
            ((it as? DefaultMutableTreeNode)?.userObject as? EnvRow.Group)?.group
        }

    // ---- actions -----------------------------------------------------------

    /**
     * The toolbar.
     *
     * *Re-read* is deliberately **not** here, next to *Sync*. The two were impossible to tell apart:
     * both were named after refreshing and both wore a circular arrow, while one installs packages
     * and takes minutes and the other just re-reads state and takes no time at all. Since the view
     * already re-reads itself — on open, whenever a manifest changes, and after every operation — a
     * permanent button for it was mostly there to be confused with the one that matters. It keeps
     * its place in the context menu and the ⋮ menu, for the case the view cannot see: something
     * installed straight into the environment behind the manifests' back.
     */
    private fun toolbarActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(SetUpAction())
        add(SyncAction())
        addSeparator()
        add(AddAction())
        add(RemoveAction())
        addSeparator()
        add(UpgradeAction())
        add(InterpreterAction())
        addSeparator()
        add(ExpandAllAction())
        add(CollapseAllAction())
    }

    private fun popupActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(AddAction())
        add(RemoveAction())
        addSeparator()
        add(SyncAction())
        add(ReReadAction())
    }

    /** The tool window's ⋮ menu, set by [EnvToolWindowFactory]. */
    fun gearActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(ReReadAction())
    }

    private fun hasEnvironment(): Boolean = service.status.environment != null

    /** The one button for a project that is not working yet; hidden once there is nothing to fix. */
    private inner class SetUpAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.setUp"),
        BasedPythonBundle.messagePointer("env.action.setUp.description"),
        AllIcons.Actions.Execute,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = service.status.health.isActionable && !service.busy
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun actionPerformed(e: AnActionEvent) = EnvOperations.setUp(project)
    }

    private inner class SyncAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.sync"),
        BasedPythonBundle.messagePointer("env.action.sync.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.status.backend != null && !service.busy
        }

        override fun actionPerformed(e: AnActionEvent) = EnvOperations.sync(project)
    }

    /** Adds to whichever group is selected — see [targetForAdd]. */
    private inner class AddAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.add"),
        BasedPythonBundle.messagePointer("env.action.add.description"),
        AllIcons.General.Add,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.status.backend != null && !service.busy
        }

        override fun actionPerformed(e: AnActionEvent) {
            val groups = service.status.dependencies.map { it.target }
            val target = EnvTreeRows.targetForAdd(selection())
            val status = service.status
            val index = status.projectRoot?.let { root -> status.backend?.packageIndex(root) }
            val request = EnvAddPackageDialog(
                project, target, groups, index, status.environment?.pythonVersion,
            ).ask() ?: return
            EnvOperations.add(project, request.requirements, request.target)
        }
    }

    /**
     * Removes the selected declared requirements, from the groups they are declared in.
     *
     * A selection spanning groups is several commands, because that is what it is: removing `pytest`
     * from `dev` and `httpx` from the main list are two edits to two lists, and one command cannot
     * express both.
     */
    private inner class RemoveAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.remove"),
        BasedPythonBundle.messagePointer("env.action.remove.description"),
        AllIcons.General.Remove,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.busy && EnvTreeRows.removable(selection()).isNotEmpty()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val selection = EnvTreeRows.removable(selection())
            if (selection.isEmpty()) return
            val described = selection.entries.joinToString("; ") { (target, names) ->
                BasedPythonBundle.message("env.remove.confirm.item", names.joinToString(", "), target.label)
            }
            val confirmed = EnvOperations.confirm(
                project,
                BasedPythonBundle.message("env.remove.confirm.title"),
                BasedPythonBundle.message("env.remove.confirm.message", described),
            )
            if (confirmed) EnvOperations.remove(project, selection)
        }
    }

    private inner class UpgradeAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.upgrade"),
        BasedPythonBundle.messagePointer("env.action.upgrade.description"),
        AllIcons.Actions.Download,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = hasEnvironment() && !service.busy
        }

        override fun actionPerformed(e: AnActionEvent) {
            val confirmed = EnvOperations.confirm(
                project,
                BasedPythonBundle.message("env.upgrade.confirm.title"),
                BasedPythonBundle.message("env.upgrade.confirm.message"),
            )
            if (confirmed) EnvOperations.upgrade(project)
        }
    }

    /**
     * Which Python the environment is built on, and the way to change it.
     *
     * Carries the current version as its label rather than an icon. There is no icon in the
     * platform's set that means "Python interpreter" — the Python logo belongs to the Python plugin,
     * which this plugin deliberately does not depend on — and every generic one that was tried read
     * as something else entirely. The version is also strictly more useful than any glyph could be:
     * the button answers "what am I on" and offers "change it" in the same place, which is what
     * PyCharm's own interpreter widget does.
     */
    private inner class InterpreterAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.interpreter"),
        BasedPythonBundle.messagePointer("env.action.interpreter.description"),
        // Typed, because a bare null cannot pick between the Icon and Supplier<Icon> overloads.
        null as Icon?,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.status.backend != null && !service.busy
            e.presentation.text = pythonLabel()
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        /** `Python 3.12` once an environment exists, and the bare invitation before one does. */
        private fun pythonLabel(): String {
            val version = service.status.environment?.pythonVersion
            return if (version.isNullOrBlank()) {
                BasedPythonBundle.message("env.action.interpreter")
            } else {
                BasedPythonBundle.message("env.action.interpreter.current", version)
            }
        }

        /**
         * The clicked button is the anchor, not the action's data context.
         *
         * The context reports this panel's *tree* — [toolbar]'s target component, which it has to be
         * for the toolbar to read the tree selection — so positioning against it puts the popup at
         * the bottom of the tool window. The input event knows where the click actually was.
         */
        override fun actionPerformed(e: AnActionEvent) =
            EnvPythonPicker.choose(project, e.inputEvent?.component, e.dataContext)
    }

    private inner class ExpandAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.expandAll"),
        BasedPythonBundle.messagePointer("env.action.expandAll"),
        AllIcons.Actions.Expandall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
    }

    private inner class CollapseAllAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.collapseAll"),
        BasedPythonBundle.messagePointer("env.action.collapseAll"),
        AllIcons.Actions.Collapseall,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            TreeUtil.collapseAll(tree, 0)
            tree.expandPath(TreePath(root))
        }
    }

    /**
     * Re-reads what is on disk into the view. Changes nothing.
     *
     * Named *Re-read* rather than *Refresh* for the same reason it left the toolbar: beside a
     * *Sync* that installs packages, a second command named after refreshing is a coin flip.
     */
    private inner class ReReadAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.reread"),
        BasedPythonBundle.messagePointer("env.action.reread.description"),
        AllIcons.Actions.ForceRefresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.busy
        }

        override fun actionPerformed(e: AnActionEvent) {
            service.refresh()
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "BasedPythonEnvironment"
        const val POPUP_PLACE = "BasedPythonEnvironmentPopup"
    }
}
