package dev.basedpython.pycharm.tasks

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Backs the "basedpython Tasks" tool window (registered in plugin.xml) with [ByTaskPanel].
 *
 * Offered to a basedpython project ([BasedPythonProjectDetector.isBasedPythonProject]) that
 * configures one of the four tools, like the test view. It used to be offered to any project that
 * did, basedpython or not, which put a "basedpython" window into every plain Python project with a
 * `.pre-commit-config.yaml` — noise for someone who never asked for this plugin's view of it.
 */
internal class ByTaskToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project) && ByTaskToolWindow.hasConfiguration(project)

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
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return
        val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        if (!window.isAvailable) window.isAvailable = true
    }
}
