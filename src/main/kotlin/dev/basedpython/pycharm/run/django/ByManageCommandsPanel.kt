package dev.basedpython.pycharm.run.django

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.GroupHeaderSeparator
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ext.ByDjangoArgument
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommandArguments
import dev.basedpython.pycharm.lsp.ext.ByDjangoProject
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Job
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

/**
 * The *manage.py* window's Commands view: a command line, the commands `by/djangoStructure` lists
 * beside the form of the one being written, whose fields and the line change each other.
 *
 * The line is what is run, whatever the list and the form know of it: a command `by` does not list and
 * an argument the form does not show are `manage.py`'s to accept or refuse.
 */
internal class ByManageCommandsPanel(private val project: Project) : BorderLayoutPanel(), Disposable {

    private val structure = ByDjangoStructureService.getInstance(project)
    private val history = ByManageHistory.getInstance(project)

    val line = JBTextField().apply {
        font = JBFont.create(UIUtil.getFontWithFallback(EditorColorsManager.getInstance().globalScheme.consoleFontName, Font.PLAIN, font.size))
        emptyText.text = BasedPythonBundle.message("manage.commands.placeholder")
        accessibleContext.accessibleName = BasedPythonBundle.message("manage.commands.line")
    }
    private val model = CollectionListModel<ByManageCommandList.Item>()
    val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
        isFocusable = true
    }
    private val form = ByManageViewPanel().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8, 12)
    }

    /** The settings module of the project whose commands are listed and run, where the session has several. */
    private val chooser = ComboBox<String>().apply {
        isVisible = false
        accessibleContext.accessibleName = BasedPythonBundle.message("manage.commands.settings")
    }

    /** The command the form is of, the state of the code it was asked in, and what it was answered. */
    private var formOf: String? = null
    private var formStamp = -1L
    private var answered: ByManageCommandArguments.State? = null
    private var arguments: ByManageArguments? = null
    private var fields: List<Field> = emptyList()
    private var asking: Job? = null

    /** What the list is narrowed by: what was typed, not what picking one of its rows wrote. */
    private var typed = ""

    /** The row was chosen with the arrows, and the next Enter takes it rather than running the line. */
    private var picked = false

    /** While [put] writes the line, which is read once it has. */
    private var writing = false

    init {
        addToTop(
            BorderLayoutPanel(JBUI.scale(6), 0).apply {
                border = JBUI.Borders.empty(6, 8)
                addToLeft(
                    BorderLayoutPanel(JBUI.scale(6), 0).apply {
                        addToLeft(chooser)
                        addToCenter(JBLabel(BasedPythonBundle.message("manage.commands.manage")).apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() })
                    },
                )
                addToCenter(line)
                addToRight(toolbar())
            },
        )
        addToCenter(
            OnePixelSplitter(false, "basedpython.manage.commands", 0.45f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(list, true)
                secondComponent = ScrollPaneFactory.createScrollPane(form, true)
            },
        )
        line.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = lineEdited()
        })
        // Tab completes a command's name, and is the focus's otherwise: see lineKey
        line.focusTraversalKeysEnabled = false
        line.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) = lineKey(e)
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // a right click is on the row under it, as a left one is
                if (e.isPopupTrigger || SwingUtilities.isRightMouseButton(e)) rowAt(e)?.let { list.selectedIndex = it }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                take(model.getElementAt(rowAt(e) ?: return))
                if (e.clickCount == 2) run(DefaultRunExecutor.getRunExecutorInstance())
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    take(list.selectedValue ?: return)
                    IdeFocusManager.getInstance(project).requestFocus(line, true)
                    e.consume()
                }
            }
        })
        chooser.addActionListener {
            if (!filling) {
                formOf = null
                lineChanged()
            }
        }
        PopupHandler.installPopupMenu(list, DefaultActionGroup(Star(), Source()), "BasedPythonManageCommandsPopup")
        structure.watch(this) { ApplicationManager.getApplication().invokeLater({ lineChanged() }, project.disposed) }
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) = serverChanged()
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) = serverChanged()
            },
        )
        // an edit can add a command or an app; asked again only while it is on screen
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { if (isShowing) structure.refresh() })
        structure.refresh()
        lineChanged()
    }

    /** What the last `by` said, or that it could not, is not what this one would. */
    private fun serverChanged() {
        structure.refresh()
        ApplicationManager.getApplication().invokeLater(
            {
                formStamp = -1
                lineChanged()
            },
            project.disposed,
        )
    }

    /** The row [e] is on; the space below the last is no row's. */
    private fun rowAt(e: MouseEvent): Int? =
        list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it).contains(e.point) }

    override fun dispose() {
        asking?.cancel()
    }

    /** Writes [text] as the line and puts the focus there. */
    fun edit(text: String) {
        typed = text
        put(text)
        IdeFocusManager.getInstance(project).requestFocus(line, true)
    }

    /** The projects that have commands to run, and the one chosen of them. */
    private fun projects() = structure.current()?.projects.orEmpty()

    private fun chosen(): ByDjangoProject? = projects().let { all -> all.getOrNull(chooser.selectedIndex) ?: all.firstOrNull() }

    private var filling = false

    private fun choices() {
        val names = projects().map { it.settings?.name.orEmpty() }
        if (names == (0 until chooser.itemCount).map(chooser::getItemAt)) return
        filling = true
        try {
            val selected = chooser.selectedItem
            chooser.removeAllItems()
            names.forEach(chooser::addItem)
            if (selected in names) chooser.selectedItem = selected
        } finally {
            filling = false
        }
        chooser.isVisible = names.size > 1
    }

    /** Why there are no commands, or what there being none means. */
    private fun why(): @Nls String = BasedPythonBundle.message(
        when (structure.state.value) {
            ByDjangoStructureService.State.Idle -> "manage.commands.idle"
            ByDjangoStructureService.State.NoServer -> "manage.commands.noServer"
            ByDjangoStructureService.State.Unsupported -> "manage.commands.unsupported"
            ByDjangoStructureService.State.Failed -> "django.structure.failed"
            ByDjangoStructureService.State.NotDjango -> "django.structure.notDjango"
            is ByDjangoStructureService.State.Ready -> "manage.commands.none"
        },
    )

    private fun fill() {
        choices()
        list.emptyText.text = why()
        val selected = list.selectedValue?.let(::nameOf)
        val items = ByManageCommandList.items(listOfNotNull(chosen()), history.lines, history.starred, typed)
        if (items != model.items) model.replaceAll(items)
        list.selectedIndex = items.indexOfFirst { nameOf(it) == selected }
        if (list.selectedIndex < 0) picked = false
    }

    /** What a row is, however it is drawn: a command starred is the same one. */
    private fun nameOf(item: ByManageCommandList.Item): String = when (item) {
        is ByManageCommandList.Item.Recent -> "> " + item.line
        is ByManageCommandList.Item.Command -> item.name
    }

    private var edited = false

    /** An edit that replaces text is a removal and an insertion: it is read once both have happened. */
    private fun lineEdited() {
        if (writing || edited) return
        edited = true
        SwingUtilities.invokeLater {
            edited = false
            if (!project.isDisposed) {
                typed = line.text
                picked = false
                pending = emptySet()
                lineChanged()
            }
        }
    }

    private fun lineChanged() {
        buttons.updateActionsAsync()
        fill()
        showForm()
    }

    /** Makes [text] the line, as one change: setting a field's text is a removal and an insertion. */
    private fun put(text: String) {
        writing = true
        try {
            line.text = text
        } finally {
            writing = false
        }
        line.caretPosition = text.length
        lineChanged()
    }

    /** Up and Down move in the list, Tab and then Enter take the row they chose, and Enter runs the line. */
    private fun lineKey(e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.VK_ENTER -> {
                if (e.modifiersEx != 0) return
                val item = list.selectedValue
                if (picked && item != null) take(item) else run(DefaultRunExecutor.getRunExecutorInstance())
            }
            KeyEvent.VK_DOWN -> move(1)
            KeyEvent.VK_UP -> move(-1)
            KeyEvent.VK_TAB -> when {
                e.isShiftDown -> line.transferFocusBackward()
                picked && list.selectedValue != null -> take(list.selectedValue)
                completes() -> take(model.items.first { it is ByManageCommandList.Item.Command })
                else -> line.transferFocus()
            }
            else -> return
        }
        e.consume()
    }

    /** Whether the name being typed is one a command listed has the beginning of, and not yet the whole of one. */
    private fun completes(): Boolean {
        val name = line.text.trimStart()
        return name.isNotEmpty() && ' ' !in name && model.items.any { it is ByManageCommandList.Item.Command && it.name.contains(name, ignoreCase = true) }
    }

    private fun move(by: Int) {
        if (model.isEmpty) return
        list.selectedIndex = (list.selectedIndex + by).coerceIn(0, model.size - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
        picked = true
    }

    /** Makes [item] the line; a command that already is its first word keeps the rest. */
    private fun take(item: ByManageCommandList.Item) {
        picked = false
        when (item) {
            is ByManageCommandList.Item.Recent -> put(item.line)
            is ByManageCommandList.Item.Command -> if (words().firstOrNull() != item.name) put(item.name + " ") else showForm()
        }
    }

    private fun words(): List<String> = ParametersListUtil.parse(line.text.trim())

    private fun run(executor: Executor) {
        val words = words().ifEmpty { return }
        val manage = ByDjangoManage.find(project, chosen())
        if (manage == null) {
            NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
                .createNotification(BasedPythonBundle.message("manage.commands.noManage"), NotificationType.WARNING)
                .notify(project)
            return
        }
        ByManageLauncher.run(project, manage, words, executor, select = false)
        fill()
    }

    // ---- the form ----------------------------------------------------------

    /** One argument's component; [show] puts its values there, and [values] reads them. */
    private class Field(val argument: ByDjangoArgument, val show: (List<String>) -> Unit, val values: () -> List<String>)

    /** The form of the command the line names, asked for again when the code or the server has changed. */
    private fun showForm() {
        val command = words().firstOrNull()
        val service = ByManageCommandArguments.getInstance(project)
        val stamp = service.stamp()
        if (command != null && command == formOf && stamp == formStamp) {
            readForm()
            return
        }
        val known = chosen()?.takeIf { project -> project.commands.any { it.name == command } }
        if (command == null || known == null) {
            asking?.cancel()
            formOf = null
            setArguments(null)
            when {
                command == null -> showMessage(BasedPythonBundle.message("manage.form.pick"))
                structure.current() == null -> showMessage(why(), helpOf = command)
                else -> showMessage(BasedPythonBundle.message("manage.form.unlisted"))
            }
            return
        }
        val again = command == formOf && answered != null
        asking?.cancel()
        formOf = command
        formStamp = stamp
        if (again) {
            readForm()
        } else {
            setArguments(null)
            showMessage(BasedPythonBundle.message("manage.form.asking", command))
        }
        asking = service.ask(command, known.settings?.name) { state ->
            ApplicationManager.getApplication().invokeLater({ if (formOf == command && formStamp == stamp) showAnswer(command, state) }, project.disposed)
        }
    }

    private fun setArguments(read: ByManageArguments?, shown: List<Field> = emptyList()) {
        arguments = read
        fields = shown
        pending = emptySet()
        if (read == null) answered = null
    }

    /** Shows [command]'s form, or why there is none. */
    internal fun showAnswer(command: String, state: ByManageCommandArguments.State) {
        if (formOf != command) {
            formOf = command
            formStamp = ByManageCommandArguments.getInstance(project).stamp()
        }
        // asked again and the same: what is being filled in stays as it is
        if (state == answered) return
        setArguments(null)
        answered = state
        when (state) {
            ByManageCommandArguments.State.NoServer -> showMessage(BasedPythonBundle.message("django.structure.noServer"))
            ByManageCommandArguments.State.Unsupported -> showMessage(BasedPythonBundle.message("manage.form.unsupported"), helpOf = command)
            ByManageCommandArguments.State.Failed -> showMessage(BasedPythonBundle.message("manage.form.failed", command), helpOf = command)
            ByManageCommandArguments.State.Unknown -> showMessage(BasedPythonBundle.message("manage.form.unlisted"))
            is ByManageCommandArguments.State.Ready -> state.arguments.error?.let { showMessage(escaped(it)) } ?: build(command, state.arguments)
        }
        // what could not be answered is asked again when the command is picked or the line changes
        if (state !is ByManageCommandArguments.State.Ready || state.arguments.error != null) formStamp = -1
    }

    private fun showMessage(text: @Nls String, helpOf: String? = null) = setForm(
        panel {
            row { text(text, MAX_LINE_LENGTH_WORD_WRAP) }
            if (helpOf != null) {
                row {
                    link(BasedPythonBundle.message("manage.form.help", helpOf)) {
                        ByDjangoManage.find(project, chosen())?.let {
                            ByManageLauncher.run(project, it, listOf(helpOf, "--help"), DefaultRunExecutor.getRunExecutorInstance(), select = false)
                        }
                    }
                }
            }
        },
    )

    private fun setForm(panel: DialogPanel) {
        form.removeAll()
        form.add(panel, BorderLayout.NORTH)
        form.revalidate()
        form.repaint()
    }

    private fun build(command: String, answer: ByDjangoCommandArguments) {
        val shown = answer.arguments.filter { it.name != null }
        val read = ByManageArguments(shown)
        val built = mutableListOf<Field>()
        setForm(
            panel {
                row { label(command).bold() }
                answer.help?.takeIf { it.isNotBlank() }?.let { help -> row { text(escaped(help), MAX_LINE_LENGTH_WORD_WRAP) } }
                shown.filterNot { it.general }.forEach { built += argumentRow(it) }
                val general = shown.filter { it.general }
                if (general.isNotEmpty()) collapsibleGroup(BasedPythonBundle.message("manage.form.general")) { general.forEach { built += argumentRow(it) } }
            },
        )
        setArguments(read, built)
        readForm()
    }

    private fun Panel.argumentRow(argument: ByDjangoArgument): Field {
        val name = argument.flags.lastOrNull { it.startsWith("--") } ?: argument.flags.lastOrNull() ?: argument.metavar ?: argument.name.orEmpty()
        lateinit var field: Field
        // a star is what the platform's forms mark a field that has to be filled with
        val label = if (argument.required) "$name *" else name
        when {
            argument.kind == ByDjangoArgument.FLAG -> {
                val box = JBCheckBox(name)
                field = Field(argument, { box.isSelected = it.isNotEmpty() }, { if (box.isSelected) listOf("") else emptyList() })
                box.addActionListener { writeLine(field) }
                row { cell(box).also { cell -> argument.help?.let { cell.comment(escaped(it), MAX_LINE_LENGTH_WORD_WRAP) } } }
            }
            argument.choices.isNotEmpty() && argument.kind == ByDjangoArgument.VALUE || argument.kind == ByDjangoArgument.COUNT -> {
                val counted = argument.kind == ByDjangoArgument.COUNT
                val choices = if (counted) (1..9).map { it.toString() } else argument.choices
                val box = ComboBox((listOf("") + choices).toTypedArray())
                field = Field(
                    argument,
                    { values -> box.selectedItem = if (counted) values.size.takeIf { it > 0 }?.toString().orEmpty() else values.lastOrNull()?.takeIf { it in choices }.orEmpty() },
                    { (box.selectedItem as? String).orEmpty().let { if (it.isEmpty()) emptyList() else if (counted) List(it.toInt()) { "" } else listOf(it) } },
                )
                box.addActionListener { writeLine(field) }
                row(label) { cell(box).also { cell -> argument.help?.let { cell.comment(escaped(it), MAX_LINE_LENGTH_WORD_WRAP) } } }
            }
            else -> {
                val several = argument.kind == ByDjangoArgument.LIST || argument.kind == ByDjangoArgument.APPEND
                val text = JBTextField()
                argument.default?.let { text.emptyText.text = it }
                field = Field(
                    argument,
                    { values -> (if (several) ParametersListUtil.join(values) else values.lastOrNull().orEmpty()).let { if (it != text.text) text.text = it } },
                    { if (text.text.isBlank()) emptyList() else if (several) ParametersListUtil.parse(text.text) else listOf(text.text) },
                )
                text.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = writeLine(field)
                })
                row(label) { cell(text).align(AlignX.FILL).also { cell -> argument.help?.let { cell.comment(escaped(it), MAX_LINE_LENGTH_WORD_WRAP) } } }
            }
        }
        return field
    }

    private fun escaped(text: String): @Nls String = StringUtil.escapeXmlEntities(text)

    private var reading = false

    /** The positionals typed in the form that the line cannot say until one before them is given. */
    private var pending: Set<ByDjangoArgument> = emptySet()

    /** Puts what the line gives each argument in its field, but for the one being written in. */
    private fun readForm() {
        val arguments = arguments ?: return
        val given = arguments.read(words().drop(1))
        reading = true
        try {
            fields.forEach { field -> if (field !== written && field.argument !in pending) field.show(given[field.argument].orEmpty()) }
        } finally {
            reading = false
        }
    }

    private var written: Field? = null

    private fun writeLine(field: Field) {
        val arguments = arguments ?: return
        if (reading) return
        val words = words()
        val rest = if (field.argument.flags.isEmpty()) {
            val all = fields.filter { it.argument.flags.isEmpty() }.associate { it.argument to it.values() }
            val line = arguments.writePositionals(words.drop(1), all)
            pending = line.pending
            line.words
        } else {
            arguments.write(words.drop(1), field.argument, field.values())
        }
        written = field
        try {
            put(ParametersListUtil.join(words.take(1) + rest))
        } finally {
            written = null
        }
    }

    // ---- actions -----------------------------------------------------------

    private lateinit var buttons: ActionToolbar

    private fun toolbar(): JComponent {
        val run = Run(DefaultRunExecutor.getRunExecutorInstance())
        val debug = Run(DefaultDebugExecutor.getDebugExecutorInstance())
        debug.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK)), line, this)
        buttons = ActionManager.getInstance().createActionToolbar("BasedPythonManageCommands", DefaultActionGroup(run, debug), true)
        buttons.targetComponent = line
        return buttons.component
    }

    private inner class Run(private val executor: Executor) : DumbAwareAction(executor.actionName, null, executor.icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = line.text.isNotBlank()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = run(executor)
    }

    private fun selectedCommand(): ByManageCommandList.Item.Command? = list.selectedValue as? ByManageCommandList.Item.Command

    private inner class Star : DumbAwareToggleAction(BasedPythonBundle.message("manage.commands.star"), null, AllIcons.Nodes.Favorite) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = selectedCommand() != null
        }

        override fun isSelected(e: AnActionEvent): Boolean = selectedCommand()?.starred == true

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            history.star(selectedCommand()?.name ?: return, state)
            fill()
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /** To the `Command` class. */
    private inner class Source : DumbAwareAction(BasedPythonBundle.messagePointer("manage.commands.source"), AllIcons.Actions.EditSource) {
        private fun symbol() = selectedCommand()?.let { item -> chosen()?.commands?.firstOrNull { it.name == item.name }?.symbol }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = symbol() != null
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val symbol = symbol() ?: return
            val file = symbol.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return
            val at = (symbol.selectionRange ?: symbol.range)?.start
            OpenFileDescriptor(project, file, at?.line ?: 0, at?.character ?: 0).navigate(true)
        }
    }

    /** A row, under its group's name where the group starts. Reads the list alone: it is painted. */
    private class Renderer : ColoredListCellRenderer<ByManageCommandList.Item>() {
        private val header = GroupHeaderSeparator(JBUI.insets(3, 8))
        private val both = BorderLayoutPanel().apply { isOpaque = false }

        override fun getListCellRendererComponent(list: JList<out ByManageCommandList.Item>, value: ByManageCommandList.Item, index: Int, selected: Boolean, hasFocus: Boolean): Component {
            val row = super.getListCellRendererComponent(list, value, index, selected, hasFocus)
            val before = if (index > 0) list.model.getElementAt(index - 1).group else null
            if (value.group == before) return row
            header.caption = value.group
            header.setHideLine(index == 0)
            both.removeAll()
            both.addToTop(header)
            both.addToCenter(row)
            return both
        }

        override fun customizeCellRenderer(list: JList<out ByManageCommandList.Item>, value: ByManageCommandList.Item, index: Int, selected: Boolean, hasFocus: Boolean) {
            when (value) {
                is ByManageCommandList.Item.Recent -> {
                    icon = AllIcons.Vcs.History
                    append(value.line)
                }
                is ByManageCommandList.Item.Command -> {
                    icon = if (value.starred) AllIcons.Nodes.Favorite else AllIcons.Nodes.Console
                    append(value.name)
                    append("  " + value.help, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    if (value.app.isNotEmpty()) append("  " + value.app, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }
}
