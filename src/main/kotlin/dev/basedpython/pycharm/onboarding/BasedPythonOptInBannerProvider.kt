package dev.basedpython.pycharm.onboarding

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.basedpython.pycharm.lang.dialect.BasedPythonOptIn
import dev.basedpython.pycharm.lang.dialect.ProjectOptIn
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.ui.BasedPythonSettingsPage
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.function.Function
import javax.swing.JComponent

/**
 * Asks, above a project's own `.py` and `.pyi` files, whether basedpython should be used for them —
 * in this project, or in every project — when nothing has answered that yet ([BasedPythonOptIn]).
 *
 * Installing the plugin and opening a Python project that does not name basedpython used to do
 * nothing at all: `by` and `buff` start only for a project with a basedpython marker, the welcome
 * notification waited for one too, and the only way in was writing a marker by hand. This is the way
 * in, on the files it is about, and it stays there until it is answered, as a balloon would not.
 *
 * Any Python file of the project's own, whatever the project looks like: a file of it being
 * open is the evidence that there is Python here. Not a library's or the interpreter's, which
 * belong to no project the answer could be about; and not where `by` is switched off for the
 * project, since that is already an answer.
 */
internal class BasedPythonOptInBannerProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (file.extension?.lowercase() !in PYTHON_EXTENSIONS) return null
        if (!BasedPythonSettings.getInstance(project).byEnabled) return null
        if (!BasedPythonOptIn.shouldAsk(project)) return null
        if (!isProjects(project, file)) return null
        return Function { editor -> panel(project, editor) }
    }

    /**
     * Whether [file] is one of [project]'s own: in its content, or — for a project with no module,
     * which is what IntelliJ IDEA makes of a plain Python directory when no Python plugin is
     * installed (measured: `modules=[]`, so nothing is content) — under its base directory. Never a
     * library's, nor in an excluded directory.
     */
    private fun isProjects(project: Project, file: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        if (index.isInLibrary(file) || index.isExcluded(file)) return false
        if (index.isInContent(file)) return true
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return false
        return VfsUtilCore.isAncestor(base, file, true)
    }

    private fun panel(project: Project, editor: FileEditor): EditorNotificationPanel =
        EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
            text = BasedPythonBundle.message("banner.optIn.text")
            createActionLabel(BasedPythonBundle.message("banner.optIn.project")) {
                BasedPythonOptIn.choose(project, ProjectOptIn.USE)
            }
            createActionLabel(BasedPythonBundle.message("banner.optIn.ide")) {
                BasedPythonOptIn.chooseForIdeFrom(project)
            }
            createActionLabel(BasedPythonBundle.message("banner.optIn.skip")) {
                BasedPythonOptIn.choose(project, ProjectOptIn.SKIP)
            }
            createActionLabel(BasedPythonBundle.message("banner.optIn.settings")) {
                BasedPythonSettingsPage.show(project)
            }
        }

    private companion object {
        val PYTHON_EXTENSIONS = setOf("py", "pyi")
    }
}
