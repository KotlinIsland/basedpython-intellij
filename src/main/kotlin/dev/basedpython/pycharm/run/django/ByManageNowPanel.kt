package dev.basedpython.pycharm.run.django

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunContentWithExecutorListener
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.setToolTipText
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * The *manage.py* window's Now view: the commands running with what each has said, what django says is
 * to be done, and how the last ones ended ([ByManageNow]).
 *
 * A row is a panel of its own, kept from one change to the next, so a link keeps the focus while a
 * server behind it logs requests.
 */
internal class ByManageNowPanel(private val project: Project, private val edit: (String) -> Unit) : SimpleToolWindowPanel(true, true), Disposable {

    private val runs = ByManageRuns.getInstance(project)
    private val list = ByManageViewPanel()
    private val rows = mutableMapOf<String, RowPanel>()

    /** The keys of what is laid out, by section: the same means the rows only change what they say. */
    private var laidOut: List<Pair<String, List<String>>> = emptyList()
    private val pending = AtomicBoolean()

    /** For how long each has run; only while the view shows. */
    private val clock = Timer(CLOCK_MS) { if (runs.state.value.runs.any { it.running }) draw() }

    init {
        list.layout = GridBagLayout()
        list.border = JBUI.Borders.empty(4, 8)
        list.withEmptyText(BasedPythonBundle.message("manage.now.empty"))
        setContent(ScrollPaneFactory.createScrollPane(list, true))
        toolbar = ActionManager.getInstance().createActionToolbar("BasedPythonManageNow", actions(), true)
            .also { it.targetComponent = list }.component
        runs.watch(this) { later() }
        // a console that has gone takes its links with it
        project.messageBus.connect(this).subscribe(
            RunContentManager.TOPIC,
            object : RunContentWithExecutorListener {
                override fun contentSelected(descriptor: RunContentDescriptor?, executor: Executor) = later()
                override fun contentRemoved(descriptor: RunContentDescriptor?, executor: Executor) = later()
            },
        )
        draw()
    }

    /** Not shown: what changes is drawn when it is. */
    private var hidden = false

    override fun addNotify() {
        super.addNotify()
        hidden = false
        clock.start()
        draw()
    }

    override fun removeNotify() {
        clock.stop()
        hidden = true
        super.removeNotify()
    }

    override fun dispose() = clock.stop()

    /** Changes that arrive before the EDT has drawn the last are one drawing. */
    private fun later() {
        if (!pending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater(
            {
                pending.set(false)
                draw()
            },
            project.disposed,
        )
    }

    private fun draw() {
        if (hidden) return
        val state = runs.state.value
        val outputs = state.runs.mapNotNull { run -> run.id.takeIf { descriptor(it) != null } }.toSet()
        val sections = ByManageNow.sections(state, outputs, System.currentTimeMillis())
        val shape = sections.map { section -> section.title to section.rows.map { it.key } }
        val keys = shape.flatMap { it.second }.toSet()
        rows.keys.retainAll(keys)
        for (row in sections.flatMap { it.rows }) rows.getOrPut(row.key) { RowPanel() }.show(row)
        if (shape == laidOut) return
        laidOut = shape
        list.removeAll()
        val at = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
        }
        for (section in sections) {
            list.add(TitledSeparator(section.title), at)
            for (row in section.rows) list.add(rows.getValue(row.key), at)
        }
        at.weighty = 1.0
        if (sections.isNotEmpty()) list.add(JPanel().apply { isOpaque = false }, at)
        list.revalidate()
        list.repaint()
    }

    private inner class RowPanel : BorderLayoutPanel() {
        private val text = SimpleColoredComponent()
        private val links = JPanel(HorizontalLayout(10, SwingConstants.CENTER)).apply { isOpaque = false }
        private var shown: ByManageNow.Row? = null

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4)
            text.isOpaque = false
            addToCenter(text)
            addToRight(links)
        }

        /** The text gives way to the links. */
        override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

        fun show(row: ByManageNow.Row) {
            val before = shown
            if (before == row) return
            shown = row
            text.clear()
            // an animated icon repaints the component it is the icon of
            text.icon = icon(row.state)
            text.append(row.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            text.append("   " + row.detail, if (row.state == ByManageNow.State.FAILED) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            // what the command printed is text, whatever it looks like
            text.setToolTipText(HtmlChunk.text(row.detail))
            if (before?.links != row.links) {
                links.removeAll()
                row.links.forEach { link ->
                    links.add(ActionLink(ByManageNow.label(link)) { follow(link) }.apply {
                        ByManageNow.description(link)?.let { setToolTipText(HtmlChunk.text(it)) }
                        // the same word is on every row
                        accessibleContext.accessibleName = text + " " + row.title
                    })
                }
                links.revalidate()
                links.repaint()
            }
        }
    }

    private fun icon(state: ByManageNow.State): Icon = when (state) {
        ByManageNow.State.RUNNING -> AnimatedIcon.Default.INSTANCE
        ByManageNow.State.SERVING -> AllIcons.Actions.Execute
        ByManageNow.State.WAITING -> AllIcons.General.ContextHelp
        ByManageNow.State.WARNING -> AllIcons.General.Warning
        ByManageNow.State.PASSED -> AllIcons.RunConfigurations.TestPassed
        ByManageNow.State.FAILED -> AllIcons.RunConfigurations.TestError
        ByManageNow.State.STOPPED -> AllIcons.RunConfigurations.TestTerminated
    }

    private fun follow(link: ByManageNow.Link) {
        when (link) {
            is ByManageNow.Link.Browse -> BrowserUtil.browse(link.url)
            is ByManageNow.Link.Output -> output(link.run)
            is ByManageNow.Link.Answer -> output(link.run)
            is ByManageNow.Link.Stop -> runs.stop(link.run)
            is ByManageNow.Link.Again -> again(link.run, runs.state.value.run(link.run)?.executorId ?: DefaultRunExecutor.EXECUTOR_ID)
            is ByManageNow.Link.Debug -> again(link.run, DefaultDebugExecutor.EXECUTOR_ID)
            is ByManageNow.Link.Edit -> edit(link.arguments)
            is ByManageNow.Link.Open -> open(link.run, link.path)
            is ByManageNow.Link.Fix -> runs.saidBy(link.warned)?.let {
                ByManageLauncher.run(project, it, ByManageNow.fix(link.warned), DefaultRunExecutor.getRunExecutorInstance())
            }
        }
    }

    private fun descriptor(id: Long): RunContentDescriptor? {
        val handler = runs.process(id)?.handler ?: return null
        return RunContentManager.getInstance(project).allDescriptors.firstOrNull { it.processHandler === handler }
    }

    /**
     * The run's own console, in the Run or Debug window, with the focus, so what is typed next is its
     * answer: the platform's own way to the front shows the console and leaves the focus where it is.
     */
    private fun output(id: Long) {
        val descriptor = descriptor(id) ?: return
        val window = RunContentManager.getInstance(project).getToolWindowByDescriptor(descriptor) ?: return
        val content = descriptor.attachedContent
        window.activate({ content?.manager?.setSelectedContent(content, true) }, true, true)
    }

    /** The runs stopped here to be run again, until they have been. */
    private val restarting = mutableSetOf<Long>()

    /**
     * Runs [id]'s command once more, with the configuration it ran with while that still says the same.
     * One still running is stopped first and has ended by then: a server's port is free again.
     */
    private fun again(id: Long, executorId: String) {
        val executor = ExecutorRegistry.getInstance().getExecutorById(executorId) ?: return
        val run = runs.state.value.run(id) ?: return
        val process = runs.process(id) ?: return
        val handler = process.handler?.takeIf { run.running }
        if (handler == null) {
            start(run, process, executor)
            return
        }
        // a second click would be a second Stop, which kills
        if (!restarting.add(id)) return
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) = ApplicationManager.getApplication().invokeLater(
                { if (restarting.remove(id)) start(run, process, executor) },
                project.disposed,
            )
        })
        runs.stop(id)
        // it had ended as it was being stopped, and told nobody
        if (handler.isProcessTerminated && restarting.remove(id)) start(run, process, executor)
    }

    private fun start(run: ByManageRun, process: ByManageRuns.Process, executor: Executor) {
        val settings = process.settings
        if (settings != null && (settings.configuration as? ByRunConfiguration)?.options == process.snapshot.options) {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        } else {
            ByManageLauncher.run(project, process.snapshot, run.words, executor)
        }
    }

    private fun open(id: Long, path: String) = runs.find(id, path) { file ->
        if (file != null) {
            OpenFileDescriptor(project, file).navigate(true)
        } else {
            // a `by` that stages the project runs the command in a copy of it, gone with the run
            NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
                .createNotification(BasedPythonBundle.message("manage.now.gone", path), NotificationType.WARNING)
                .notify(project)
        }
    }

    private fun actions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction(BasedPythonBundle.messagePointer("manage.now.clear"), AllIcons.Actions.GC) {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = runs.state.value.runs.any { !it.running }
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

            override fun actionPerformed(e: AnActionEvent) = runs.clearFinished()
        })
    }

    private companion object {
        const val CLOCK_MS = 1_000
    }
}

/** A view's content, as wide as the view, so nothing of it is past the edge; says what is missing while it is empty. */
internal class ByManageViewPanel : JBPanelWithEmptyText(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = preferredSize.height < (parent?.height ?: 0)
}
