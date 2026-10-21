package dev.basedpython.pycharm.run.build

import com.intellij.build.BuildContentManager
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/**
 * The Build window's tabs that show a basedpython build, closed when the plugin unloads.
 *
 * The window keeps a build until a later one replaces it, and with it the build's
 * [ByBuildView.Stop] — the console's running state and the build's descriptor both hold it. In
 * IntelliJ IDEA, the JPS build that runs beside ours keeps the whole task context in its Rerun
 * action, this plugin's task runner included. Nothing in the platform lets go of either when the
 * plugin unloads, and either keeps the plugin's classes loaded (measured: an unload after Build |
 * Build Project left the classloader reachable through both, and through neither once the tab was
 * closed). So the tab goes with the plugin, its build stopped first if it is still running, so that
 * closing it has nothing to ask.
 *
 * A build still running then is held all the same, by the platform: the window counts a tab live
 * until it has handled the build's end, which it does after the tab is gone, and keeps a live tab
 * (measured on 263.5701 with a `by build` that took two minutes).
 */
@Service(Service.Level.PROJECT)
internal class ByBuildWindow(private val project: Project) {

    /** Each console a build of ours is shown in, with its Stop; weakly, so a replaced one goes. */
    private val shown = WeakHashMap<ConsoleView, ByBuildView.Stop>()

    fun shown(console: ConsoleView, stop: ByBuildView.Stop) {
        synchronized(shown) { shown[console] = stop }
    }

    /** Stops the builds still running and closes every Build tab showing one of ours. On the EDT. */
    fun close() {
        val builds = synchronized(shown) { shown.toMap().also { shown.clear() } }
        if (builds.isEmpty()) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(BuildContentManager.TOOL_WINDOW_ID) ?: return
        val contents = window.contentManager
        for (content in contents.contents) {
            val ours = builds.filterKeys { SwingUtilities.isDescendingFrom(it.component, content.component) }
            if (ours.isEmpty()) continue
            ours.values.forEach(ByBuildView.Stop::abandon)
            contents.removeContent(content, true)
        }
    }

    companion object {
        fun getInstance(project: Project): ByBuildWindow = project.service()
    }
}

/** Calls [ByBuildWindow.close] in every open project when it is this plugin that is unloading. */
class ByBuildWindowUnloadGuard : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != BundledBinaries.PLUGIN_ID) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) project.serviceIfCreated<ByBuildWindow>()?.close()
        }
    }
}
