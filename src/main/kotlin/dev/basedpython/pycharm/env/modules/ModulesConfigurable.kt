package dev.basedpython.pycharm.env.modules

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.env.manager.EnvService
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * *Settings | Languages & Frameworks | basedpython | Modules* — the parts a project is built from,
 * and the three things that can be done to one.
 *
 * ### Why this is a settings page and not a tool window
 *
 * Because of what it is: the shape of the project. That is the question the platform's own *Project
 * Structure* dialog answers for a JVM project, and it is a thing you open, change, and close —
 * unlike the environment view, which is a state you watch. A fourth stripe button for a screen
 * visited when a module is added would be a permanent cost for an occasional gesture.
 *
 * ### Why nothing here waits for *Apply*
 *
 * A settings page normally collects edits and commits them when the user presses OK. This one acts
 * immediately, and [isModified] is therefore always false. The reason is that the operations are not
 * settings: creating a module runs `uv init`, which writes files and edits the project's manifest,
 * and removing one deletes a directory. Queuing those behind an *Apply* would mean a dialog whose
 * *Cancel* cannot undo what it appears to be able to undo — and would need a second implementation
 * of every operation, one that pretends. So this page is a view of what is on disk, in the shape of
 * the platform's own *Project Structure*: the table is truth, the buttons act, and the notifications
 * and the log say what happened. It is the same rule the environment tool window follows, and the
 * same rule that makes the two of them impossible to disagree.
 */
internal class ModulesConfigurable(private val project: Project) : SearchableConfigurable {

    private val service = EnvService.getInstance(project)

    private val model = ModuleTableModel()

    private val table = JBTable(model).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = BasedPythonBundle.message("modules.empty")
        // A table of six short columns has no useful column to give the slack to, and the default
        // auto-resize squeezes the path — the one column whose content varies in length.
        autoResizeMode = JBTable.AUTO_RESIZE_ALL_COLUMNS
    }

    /** The note above the table: what a module is, or why there are none to show. */
    private val note = JBLabel().apply {
        border = JBUI.Borders.emptyBottom(8)
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Lives as long as the page's component does, which is not as long as the page object does. */
    private var uiDisposable: Disposable? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = BasedPythonBundle.message("modules.page.title")

    override fun createComponent(): JComponent {
        val disposable = Disposer.newDisposable("basedpython modules page")
        uiDisposable = disposable
        service.addListener(disposable) { render() }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val module = selected() ?: return false
                edit(module)
                return true
            }
        }.installOn(table)

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { create() }
            .setAddActionUpdater { canCreate() }
            .setRemoveAction { selected()?.let { remove(it) } }
            .setRemoveActionUpdater { selected()?.isRoot == false && !service.busy }
            .setEditAction { selected()?.let { edit(it) } }
            .setEditActionUpdater { selected() != null && !service.busy }
            .addExtraAction(OpenManifestAction())
            .disableUpDownActions()
            .createPanel()

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(
                JBPanel<JBPanel<*>>().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(note)
                },
                BorderLayout.NORTH,
            )
            add(decorated, BorderLayout.CENTER)
        }

        render()
        service.refreshIfNeeded()
        return panel
    }

    /** Nothing is pending — see the class documentation. */
    override fun isModified(): Boolean = false

    override fun apply() = Unit

    /** Re-reads the project rather than restoring fields: there are no fields to restore. */
    override fun reset() {
        render()
    }

    override fun disposeUIResources() {
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
    }

    // ---- rendering ---------------------------------------------------------

    private fun render() {
        val status = service.status
        val modules = status.modules
        model.setModules(modules?.all.orEmpty(), modules)
        val text = when {
            status.backend == null || modules == null ->
                BasedPythonBundle.message("modules.note.unmanaged")
            status.toolPath == null ->
                BasedPythonBundle.message("modules.note.toolMissing", status.backend?.displayName.orEmpty())
            !modules.isWorkspace ->
                BasedPythonBundle.message("modules.note.single")
            else ->
                BasedPythonBundle.message("modules.note.workspace", modules.memberPatterns.joinToString(", "))
        }
        // Wrapped rather than left to the label, which would render one line and cut it off at the
        // width of the settings pane. The strings are the plugin's own and carry no markup.
        note.text = "<html>$text</html>"
    }

    private fun selected(): ProjectModule? = model.moduleAt(table.selectedRow)

    private fun canCreate(): Boolean =
        !service.busy && service.status.modules != null && ModuleOperations.isSupported(service.status.backend)

    // ---- gestures ----------------------------------------------------------

    private fun create() {
        val layout = service.status.modules ?: return
        val request = NewModuleDialog(project, layout).ask() ?: return
        ModuleOperations.create(project, request)
    }

    private fun edit(module: ProjectModule) {
        val layout = service.status.modules ?: return
        val edit = EditModuleDialog(project, module, layout).ask() ?: return
        ModuleOperations.apply(project, module, edit)
    }

    /**
     * Removes [module] after asking, naming what will break and what will be deleted.
     *
     * The dependents are named in the question rather than discovered afterwards, because "remove
     * this module" and "remove this module and stop three others depending on it" are different
     * decisions, and only one of them is safe to make from a table row.
     */
    private fun remove(module: ProjectModule) {
        val layout = service.status.modules ?: return
        val request = RemoveModuleDialog(project, module, layout.dependents(module.name)).ask() ?: return
        ModuleOperations.remove(project, module, deleteFiles = request)
    }

    /** Opens the selected module's own manifest — the file every operation here ends up rewriting. */
    private inner class OpenManifestAction : AnAction(
        BasedPythonBundle.messagePointer("modules.action.openManifest"),
        BasedPythonBundle.messagePointer("modules.action.openManifest.description"),
        AllIcons.Actions.MenuOpen,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selected() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val module = selected() ?: return
            val file = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(module.root.resolve(UvWorkspace.MANIFEST)) ?: return
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    /**
     * The table: one row per module, read-only.
     *
     * Read-only because every column is something a command owns. A version typed into a cell would
     * have to be written back to a manifest on focus loss, with no confirmation and no way to see
     * what else changed; the same edit made in the dialog is one explicit gesture with a progress
     * bar and a notification behind it.
     */
    private class ModuleTableModel : AbstractTableModel() {

        private var modules: List<ProjectModule> = emptyList()
        private var layout: ModuleLayout? = null

        fun setModules(modules: List<ProjectModule>, layout: ModuleLayout?) {
            this.modules = modules
            this.layout = layout
            fireTableDataChanged()
        }

        fun moduleAt(row: Int): ProjectModule? = modules.getOrNull(row)

        override fun getRowCount(): Int = modules.size

        override fun getColumnCount(): Int = COLUMNS.size

        override fun getColumnName(column: Int): String = BasedPythonBundle.message(COLUMNS[column])

        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun getValueAt(row: Int, column: Int): Any {
            val module = modules.getOrNull(row) ?: return ""
            return when (column) {
                0 -> module.name
                1 -> BasedPythonBundle.message(typeKey(module))
                2 -> module.relativePath.ifEmpty { "." }
                3 -> module.version.orEmpty()
                4 -> module.requiresPython.orEmpty()
                else -> usedBy(module)
            }
        }

        private fun typeKey(module: ProjectModule): String = when {
            module.isRoot -> "modules.type.root"
            module.packaged -> "modules.type.library"
            else -> "modules.type.application"
        }

        /**
         * The siblings that declare this module, or a dash.
         *
         * The column the table exists for. A list of directories says what a project contains; this
         * says how the parts are joined, which is the question anyone opening this page to remove
         * something needs answered before they do.
         */
        private fun usedBy(module: ProjectModule): String {
            val dependents = layout?.dependents(module.name).orEmpty()
            return if (dependents.isEmpty()) {
                BasedPythonBundle.message("modules.usedBy.none")
            } else {
                dependents.joinToString(", ") { it.name }
            }
        }

        private companion object {
            val COLUMNS = listOf(
                "modules.column.module",
                "modules.column.type",
                "modules.column.path",
                "modules.column.version",
                "modules.column.python",
                "modules.column.usedBy",
            )
        }
    }

    companion object {
        /** Must match the `id` in plugin.xml — the settings tree and *Search everywhere* key on it. */
        const val ID: String = "dev.basedpython.pycharm.settings.modules"
    }
}

/** Creates the page. Registered in plugin.xml as a child of the basedpython settings page. */
internal class ModulesConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    override fun createConfigurable(): Configurable = ModulesConfigurable(project)
}

/**
 * *Tools | basedpython | Project Structure…* — opens the page above.
 *
 * A settings page three levels down a tree is not somewhere anybody browses to, and the gesture it
 * serves ("add a module") starts from the menu rather than from Settings. One entry, next to the
 * two the environment feature already has, rather than a menu item per operation.
 */
internal class ShowModulesAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ModulesConfigurable::class.java)
    }
}
