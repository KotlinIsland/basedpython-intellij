package dev.basedpython.pycharm.console

import com.intellij.execution.ExecutionException
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lsp.startByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The project's basedpython consoles: each a tab of the *basedpython Console* tool window, each its
 * own `by repl` session ([ByConsoleSession]).
 *
 * A project service, so the coroutines a console's Enter runs in ([scope]) and every session go with
 * the project or with the plugin.
 */
@Service(Service.Level.PROJECT)
internal class ByConsoles(private val project: Project, val scope: CoroutineScope) {

    private val sessions = CopyOnWriteArrayList<ByConsoleSession>()

    /** Which tab holds which session; the tabs are this tool window's, the map this service's. */
    private val tabs = WeakHashMap<Content, ByConsoleSession>()

    /** The session whose console input [file] is, for completion in it. */
    fun sessionFor(file: VirtualFile): ByConsoleSession? = sessions.firstOrNull { it.inputFile == file }

    /**
     * Shows the tool window and hands [then] the console in its selected tab, starting a session when
     * that one has ended or there is none. On the EDT.
     */
    fun show(then: (ByConsoleSession) -> Unit = {}) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate(null)
        val selected = toolWindow.contentManager.selectedContent?.let { synchronized(tabs) { tabs[it] } }
        if (selected != null && selected.isRunning) then(selected) else start(toolWindow, then = then)
    }

    /**
     * Starts a session in a new tab of [toolWindow], in place of [replacing] when it is given, and hands
     * it to [then] once its console exists.
     */
    fun start(toolWindow: ToolWindow, replacing: Content? = null, then: (ByConsoleSession) -> Unit = {}) {
        scope.launch {
            val process = try {
                withContext(Dispatchers.IO) { ByConsoleSession.startProcess(project) }
            } catch (e: ExecutionException) {
                NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
                    .createNotification(BasedPythonBundle.message("console.notStarted", e.message.orEmpty()), NotificationType.ERROR)
                    .notify(project)
                return@launch
            }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext process.first.destroyProcess()
                val session = ByConsoleSession.build(project, process)
                sessions += session
                Disposer.register(session) { sessions -= session }
                val content = ContentFactory.getInstance().createContent(panel(session, toolWindow), title(toolWindow), false)
                content.setDisposer(session)
                content.preferredFocusableComponent = session.console.preferredFocusableComponent
                synchronized(tabs) { tabs[content] = session }
                val manager = toolWindow.contentManager
                manager.addContent(content, replacing?.let(manager::getIndexOfContent) ?: -1)
                replacing?.let { manager.removeContent(it, true) }
                manager.setSelectedContent(content, true)
                // Enter and completion ask `by`; it is started here so it is up by the first Enter
                startByServer(project)
                then(session)
            }
        }
    }

    private fun title(toolWindow: ToolWindow): String {
        val taken = toolWindow.contentManager.contents.map { it.displayName }.toSet()
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) BasedPythonBundle.message("console.tab") else BasedPythonBundle.message("console.tab.numbered", it) }
            .first { it !in taken }
    }

    /** The tab's component: the console under its toolbar. */
    private fun panel(session: ByConsoleSession, toolWindow: ToolWindow): SimpleToolWindowPanel {
        val panel = SimpleToolWindowPanel(false, true)
        val actions = DefaultActionGroup(
            Rerun(session, toolWindow),
            Interrupt(session),
            Stop(session),
        )
        actions.addSeparator()
        actions.addAll(*session.console.createConsoleActions())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, false)
        toolbar.targetComponent = session.console.component
        panel.toolbar = toolbar.component
        panel.setContent(session.console.component)
        return panel
    }

    /** Ends the session and starts another in its tab. */
    private inner class Rerun(private val session: ByConsoleSession, private val toolWindow: ToolWindow) :
        DumbAwareAction(BasedPythonBundle.message("console.rerun"), null, AllIcons.Actions.Restart) {
        override fun actionPerformed(e: AnActionEvent) {
            session.stop()
            val content = synchronized(tabs) { tabs.entries.firstOrNull { it.value === session }?.key }
            start(toolWindow, replacing = content)
        }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private class Interrupt(private val session: ByConsoleSession) :
        DumbAwareAction(BasedPythonBundle.message("console.interrupt"), BasedPythonBundle.message("console.interrupt.description"), AllIcons.Actions.Pause) {
        override fun actionPerformed(e: AnActionEvent) = session.interrupt()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session.canInterrupt
        }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private class Stop(private val session: ByConsoleSession) :
        DumbAwareAction(BasedPythonBundle.message("console.stop"), null, AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) = session.stop()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = session.isRunning
        }
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    companion object {
        const val TOOL_WINDOW_ID = "basedpython Console"
        private const val TOOLBAR_PLACE = "basedpythonConsole"

        fun getInstance(project: Project): ByConsoles = project.service()
    }
}
