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
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
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
        return "by: ${glyph(snap.byLight)}"
    }

    /** A healthy server is quiet; only [ServerLight.PROBLEM] is meant to catch the eye. */
    private fun glyph(l: ServerLight) = when (l) {
        ServerLight.RUNNING -> "○"
        ServerLight.STOPPED -> "◌"
        ServerLight.PROBLEM -> "✕"
    }

    override fun getTooltipText(): String {
        val snap = LspServerStateService.getInstance(project).snapshot()
        return buildString {
            append("basedpython LSP\n")
            append("  by:   ").append(stateWord(snap.byLight, snap.byPath))
            byVersion?.let { append("  v").append(it) }
            append("  (").append(snap.byPath ?: "not found").append(")\n")
            append("  buff: ").append(stateWord(snap.buffLight, snap.buffPath))
            buffVersion?.let { append("  v").append(it) }
            append("  (").append(snap.buffPath ?: "not found").append(")")
        }
    }

    /**
     * [ServerLight.PROBLEM] covers both "no binary to run" and "the binary ran and then died",
     * which want different fixes — tell them apart by whether a binary was resolved at all.
     */
    private fun stateWord(l: ServerLight, path: String?) = when (l) {
        ServerLight.RUNNING -> "running"
        ServerLight.STOPPED -> "stopped"
        ServerLight.PROBLEM -> if (path == null) "binary not found" else "stopped unexpectedly"
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

    /**
     * Repaint whenever any server changes state. The widget reads live state from
     * [LspServerStateService] on each paint, so this only needs to trigger the repaint — there is
     * no state to mirror here.
     */
    private fun subscribeToLspEvents() {
        LspServerManager.getInstance(project).addLspServerManagerListener(
            object : LspServerManagerListener {
                override fun serverStateChanged(lspServer: LspServer) {
                    ApplicationManager.getApplication().invokeLater { update() }
                }
            },
            this,
            false,
        )
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
        // "basedpython" is this plugin's own tool window (plugin.xml); "Language Servers" is the
        // platform's, kept as a fallback. The branding pass left this list with the same id twice.
        val toolWindow = mgr.getToolWindow("basedpython")
            ?: mgr.getToolWindow("Language Servers")
        toolWindow?.activate(null)
    }

    companion object {
        const val WIDGET_ID = "dev.basedpython.pycharm.statusbar"
    }
}
