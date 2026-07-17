package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.util.Consumer
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.settings.ui.BasedPythonConfigurable
import java.awt.event.MouseEvent

internal class BasedPythonStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    @Volatile private var byVersion: String? = null
    @Volatile private var buffVersion: String? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Subscribe to Stream B's LSP listener if present.
        subscribeToLspEvents()
        refreshVersions()
    }

    /** Resolve binary versions off the EDT, cache them, then repaint the widget tooltip. */
    private fun refreshVersions() {
        ApplicationManager.getApplication().executeOnPooledThread {
            byVersion = dev.basedpython.pycharm.env.BasedPythonVersions.byVersion(project)
            buffVersion = dev.basedpython.pycharm.env.BasedPythonVersions.buffVersion(project)
            ApplicationManager.getApplication().invokeLater { update() }
        }
    }

    override fun dispose() {
        statusBar = null
    }

    // ---- MultipleTextValuesPresentation ----

    override fun getSelectedValue(): String {
        val snap = LspServerStateService.getInstance(project).snapshot()
        val dot = when (snap.byLight) {
            ServerLight.GREEN -> "●"
            ServerLight.GRAY -> "○"
            ServerLight.RED -> "✕"
        }
        return "by: $dot"
    }

    override fun getTooltipText(): String {
        val snap = LspServerStateService.getInstance(project).snapshot()
        return buildString {
            append("basedpython LSP\n")
            append("  by:   ").append(stateWord(snap.byLight))
            byVersion?.let { append("  v").append(it) }
            append("  (").append(snap.byPath ?: "not found").append(")\n")
            append("  buff: ").append(stateWord(snap.buffLight))
            buffVersion?.let { append("  v").append(it) }
            append("  (").append(snap.buffPath ?: "not found").append(")")
        }
    }

    private fun stateWord(l: ServerLight) = when (l) {
        ServerLight.GREEN -> "running"
        ServerLight.GRAY -> "stopped"
        ServerLight.RED -> "binary missing"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    override fun getPopup(): com.intellij.openapi.ui.popup.ListPopup? {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Restart LSP") {
                override fun actionPerformed(e: AnActionEvent) { restartLsp() }
            })
            add(object : AnAction("Open Settings…") {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, BasedPythonConfigurable::class.java)
                }
            })
            add(object : AnAction("Show Logs") {
                override fun actionPerformed(e: AnActionEvent) { showLogs() }
            })
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "basedpython",
            group,
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
    }

    private fun update() {
        statusBar?.updateWidget(WIDGET_ID)
    }

    private fun subscribeToLspEvents() {
        try {
            val topicCls = Class.forName("com.intellij.platform.lsp.api.LspServerListener")
            val topicField = topicCls.fields.firstOrNull { it.name == "TOPIC" } ?: return
            @Suppress("UNCHECKED_CAST")
            val topic = topicField.get(null) as? com.intellij.util.messages.Topic<Any> ?: return
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                topicCls.classLoader,
                arrayOf(topicCls),
            ) { _, method, args ->
                // Best-effort state caching based on method name.
                val name = method.name
                val running = name.contains("Initialized", ignoreCase = true) ||
                    name.contains("Started", ignoreCase = true)
                val stopped = name.contains("Stopped", ignoreCase = true) ||
                    name.contains("Terminated", ignoreCase = true)
                val cache = LspServerStateService.getInstance(project)
                val arg0 = args?.firstOrNull()?.toString()?.lowercase().orEmpty()
                when {
                    arg0.contains("buff") -> if (running) cache.markBuffRunning(true) else if (stopped) cache.markBuffRunning(false)
                    else -> if (running) cache.markByRunning(true) else if (stopped) cache.markByRunning(false)
                }
                ApplicationManager.getApplication().invokeLater { update() }
                null
            }
            project.messageBus.connect(this).subscribe(topic, handler)
        } catch (_: Throwable) {
            // Stream B not merged yet — widget still functions via cached state.
        }
    }

    private fun restartLsp() {
        val mgr = LspServerManager.getInstance(project)
        mgr.stopAndRestartIfNeeded(ByLspServerSupportProvider::class.java)
        mgr.stopAndRestartIfNeeded(BuffLspServerSupportProvider::class.java)
        update()
        refreshVersions()
    }

    private fun showLogs() {
        val mgr = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val toolWindow = mgr.getToolWindow("basedpython")
            ?: mgr.getToolWindow("basedpython")
            ?: mgr.getToolWindow("Language Servers")
        toolWindow?.activate(null)
    }

    companion object {
        const val WIDGET_ID = "dev.basedpython.pycharm.statusbar"
    }
}
