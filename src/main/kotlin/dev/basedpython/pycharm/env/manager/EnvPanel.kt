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
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.ListSelectionModel

/**
 * The "basedpython Environment" tool window: which environment this project runs in, what is in it,
 * and the one thing to press when something is wrong.
 *
 * ### What it is for
 *
 * PyCharm's interpreter UI answers "which interpreter is selected", which is the question an IDE
 * cares about. It is not the question a person has when their imports are red. That one is "is there
 * an environment, does it have what the project says it needs, and what do I press" — and it is
 * answered here in a banner with a button, above the package list that proves the answer.
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

    private val model = ListTableModel<EnvPackage>(NameColumn(), VersionColumn(), SourceColumn())
    private val table = TableView(model)

    /**
     * Kept so a state change can update it at once.
     *
     * A toolbar otherwise re-evaluates its actions on its own schedule, which for a sync that takes
     * a minute means the buttons it should have disabled stay live for the first second of it.
     */
    private val toolbar: ActionToolbar

    init {
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.emptyText.text = BasedPythonBundle.message("env.packages.empty")

        toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, toolbarActions(), true)
        toolbar.targetComponent = table
        setToolbar(toolbar.component)

        val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
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
        header.removeAll()
        renderBanner(status)?.let(header::add)
        summary.text = describe(status)
        header.add(summary)
        header.revalidate()
        header.repaint()

        model.items = status.packages
        table.emptyText.clear()
        table.emptyText.setText(emptyMessage(status))
        toolbar.updateActionsAsync()
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
                    createActionLabel(BasedPythonBundle.message("env.action.refresh")) { service.refresh() }
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
                    EnvPythonPicker.choose(project, table)
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

    private fun emptyMessage(status: EnvStatus): String = when {
        !service.scanned -> BasedPythonBundle.message("env.summary.scanning")
        status.environment == null -> BasedPythonBundle.message("env.packages.noEnvironment")
        else -> BasedPythonBundle.message("env.packages.empty")
    }

    // ---- columns -----------------------------------------------------------

    private class NameColumn : ColumnInfo<EnvPackage, String>(
        BasedPythonBundle.message("env.column.package"),
    ) {
        override fun valueOf(item: EnvPackage): String = item.name
    }

    private class VersionColumn : ColumnInfo<EnvPackage, String>(
        BasedPythonBundle.message("env.column.version"),
    ) {
        override fun valueOf(item: EnvPackage): String = item.version
    }

    /**
     * Where a package came from — blank for an ordinary wheel, the path for an editable install.
     *
     * The column exists for one row: the project's own package, which uv installs into the
     * environment as an editable and which otherwise reads as a mysterious dependency on itself.
     */
    private class SourceColumn : ColumnInfo<EnvPackage, String>(
        BasedPythonBundle.message("env.column.source"),
    ) {
        override fun valueOf(item: EnvPackage): String =
            item.editableLocation?.let { BasedPythonBundle.message("env.package.editable", it) }.orEmpty()
    }

    // ---- actions -----------------------------------------------------------

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
        add(RefreshAction())
    }

    private fun hasEnvironment(): Boolean = service.status.environment != null

    private fun selectedPackages(): List<EnvPackage> = table.selectedObjects.filterNotNull()

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
            val request = EnvAddPackageDialog(project).ask() ?: return
            EnvOperations.add(project, request.requirements, request.dev)
        }
    }

    /**
     * Removes the selected packages from the project's dependencies.
     *
     * Disabled for a selection that is only transitive, because removing one is not a thing the
     * backend can do: they are in the environment because something else asked for them, and a
     * `uv remove certifi` on a project that never declared it fails with a message about a
     * dependency it cannot find. The check is "did the project declare it", approximated by the
     * package not being an editable install of the project itself — the exact declared set lives in
     * `pyproject.toml`, and reading it here would be a second parser of a file uv already owns.
     */
    private inner class RemoveAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.remove"),
        BasedPythonBundle.messagePointer("env.action.remove.description"),
        AllIcons.General.Remove,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.busy && selectedPackages().any { !it.isEditable }
        }

        override fun actionPerformed(e: AnActionEvent) {
            val names = selectedPackages().filter { !it.isEditable }.map { it.name }
            if (names.isEmpty()) return
            val confirmed = EnvOperations.confirm(
                project,
                BasedPythonBundle.message("env.remove.confirm.title"),
                BasedPythonBundle.message("env.remove.confirm.message", names.joinToString(", ")),
            )
            if (confirmed) EnvOperations.remove(project, names, dev = false)
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

    private inner class InterpreterAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.interpreter"),
        BasedPythonBundle.messagePointer("env.action.interpreter.description"),
        AllIcons.Nodes.Console,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.status.backend != null && !service.busy
        }

        override fun actionPerformed(e: AnActionEvent) = EnvPythonPicker.choose(project, table)
    }

    private inner class RefreshAction : DumbAwareAction(
        BasedPythonBundle.messagePointer("env.action.refresh"),
        BasedPythonBundle.messagePointer("env.action.refresh.description"),
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
    }
}
