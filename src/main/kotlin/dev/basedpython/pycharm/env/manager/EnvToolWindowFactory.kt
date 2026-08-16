package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.nio.file.Files
import java.nio.file.Paths

/** Backs the "basedpython Environment" tool window (registered in plugin.xml) with [EnvPanel]. */
internal class EnvToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = EnvToolWindow.hasBackend(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EnvPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setAdditionalGearActions(panel.gearActions())
    }
}

/** The tool window's identity, and when the IDE is allowed to show its stripe button. */
internal object EnvToolWindow {

    /** Must match the `id` in plugin.xml — the platform keys layout and visibility on this string. */
    const val ID: String = "basedpython Environment"

    /**
     * True when the project root holds a marker some backend recognises.
     *
     * A file-existence check rather than [EnvBackends.detect], because this is asked while the
     * project is opening and must not depend on locating a tool or reading a manifest. It is
     * deliberately looser than the real answer: a `pyproject.toml` project whose backend turns out
     * not to claim it gets a stripe button and an honest "nothing to manage here" inside, which is a
     * better outcome than a window that never appears for a project the user expects it on.
     */
    fun hasBackend(project: Project): Boolean {
        val base = project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() } ?: return false
        return EnvBackends.ALL_MARKERS.any { Files.isRegularFile(base.resolve(it)) }
    }

    /**
     * Shows the stripe button for a project that has just grown its first manifest.
     *
     * One-way, exactly as the task window's is: availability was decided when the project opened, so
     * a `uv init` run in a terminal has to be able to turn the window on — but a scan that comes
     * back unmanaged must not turn it *off* and close a window the user is looking at. Must be
     * called on the EDT.
     */
    fun refreshAvailability(project: Project) {
        if (project.isDisposed) return
        if (EnvService.getInstance(project).status.backend == null) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        if (!window.isAvailable) window.isAvailable = true
    }
}
