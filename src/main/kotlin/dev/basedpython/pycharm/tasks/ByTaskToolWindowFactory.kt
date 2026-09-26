package dev.basedpython.pycharm.tasks

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Backs the "basedpython Tasks" tool window (registered in plugin.xml) with [ByTaskPanel].
 *
 * Offered to any project that configures one of the four tools, basedpython or not. That is
 * deliberate and unlike the test view: `.pre-commit-config.yaml` has nothing to do with `.by` files,
 * and a plugin that reads it should not make a Python project prove itself first. What gates the
 * window is the only thing that matters — whether there is anything in it.
 */
internal class ByTaskToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean = ByTaskToolWindow.hasConfiguration(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ByTaskPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setAdditionalGearActions(panel.gearActions())
        ByTaskService.getInstance(project).refreshIfNeeded()
    }
}

/** The tool window's identity, and when the IDE is allowed to show its stripe button. */
internal object ByTaskToolWindow {

    /** Must match the `id` in plugin.xml — the platform keys layout and visibility on this string. */
    const val ID: String = "basedpython Tasks"

    /**
     * True when the project root holds a file a scan would read.
     *
     * A file-existence check rather than a parse: this answers "should there be a stripe button",
     * which is asked while the project is opening, and the answer must not depend on reading and
     * parsing anything.
     */
    fun hasConfiguration(project: Project): Boolean {
        val base = project.basePath?.let { Paths.get(it) } ?: return false
        return ByTaskScan.FILES.any { Files.isRegularFile(base.resolve(it)) }
    }

    /**
     * Shows the stripe button for a project that has just grown its first hook configuration.
     *
     * One-way on purpose. Availability was decided when the project opened, so a config file added
     * afterwards has to be able to turn the window on — but a scan that comes back empty must not
     * turn it *off*, which would close a window the user is looking at because the file they are
     * halfway through editing does not parse yet. Must be called on the EDT.
     */
    fun refreshAvailability(project: Project) {
        if (project.isDisposed) return
        if (ByTaskService.getInstance(project).files.isEmpty()) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        if (!window.isAvailable) window.isAvailable = true
    }
}
