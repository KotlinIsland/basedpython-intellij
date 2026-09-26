package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget.WidgetPresentation
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.util.Consumer
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.settings.ui.BasedPythonSettingsPage
import java.awt.event.MouseEvent

// No compiler bridges to the deprecated getPresentation(PlatformType), getPopupStep, getMaxValue.
@JvmDefaultWithoutCompatibility
internal class BasedPythonStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Subscribe to Stream B's LSP listener if present.
        subscribeToLspEvents()
        refresh()
    }

    /** Has the binaries looked for again off the EDT, then repaints. See [LspServerStateService]. */
    private fun refresh() {
        LspServerStateService.getInstance(project).refresh { update() }
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
            snap.byVersion?.let { append("  ").append(it) }
            append("  (").append(where(snap, snap.byPath)).append(")\n")
            append("  buff: ").append(stateWord(snap.buffLight, snap.buffPath))
            snap.buffVersion?.let { append("  ").append(it) }
            append("  (").append(where(snap, snap.buffPath)).append(")")
        }
    }

    private fun where(snap: ServerSnapshot, path: String?) = when {
        !snap.resolved -> "looking…"
        else -> path ?: "not found"
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
                    BasedPythonSettingsPage.show(project)
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
     * Repaint when a server starts or stops. The widget reads live server state from
     * [LspServerStateService] on each paint; what a start or stop can also change is which binary
     * resolves (a restart is how a changed setting takes effect), so that is looked for again first.
     *
     * Was the platform's `LspServerManagerListener`, which is `@ApiStatus.Internal`; see
     * [ByLspLifecycleListener]. That one fired on every state change, this one only on the two
     * ends, and the light is the same either way — [LspServerStateService] maps `Initializing` and
     * `Running` to the same `ServerLight.RUNNING`, so the only transition that changes what is
     * drawn is stopped to running, which both of these cover. The one difference is when: the
     * light now turns green as the server becomes ready rather than as it begins starting.
     */
    private fun subscribeToLspEvents() {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) = refresh()
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) = refresh()
            },
        )
    }

    private fun restartLsp() {
        val mgr = LspClientManager.getInstance(project)
        mgr.stopAndRestartClientsIfNeeded(ByLspServerSupportProvider::class.java)
        mgr.stopAndRestartClientsIfNeeded(BuffLspServerSupportProvider::class.java)
        refresh()
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
