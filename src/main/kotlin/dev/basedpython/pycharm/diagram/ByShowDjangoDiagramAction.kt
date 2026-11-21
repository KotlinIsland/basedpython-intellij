package dev.basedpython.pycharm.diagram

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.diagram.DiagramFileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.uml.UmlVirtualFileSystem
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * *Show Model Diagram*, in the *basedpython Django* tool window: the Diagrams plugin's diagram of
 * every model of the project ([ByDjangoDiagramProvider.ALL]) in an editor tab.
 * Shown once `by` has answered with a structure.
 */
internal class ByShowDjangoDiagramAction : DumbAwareAction(
    BasedPythonBundle.message("django.diagram.show"),
    BasedPythonBundle.message("django.diagram.show.description"),
    AllIcons.FileTypes.Diagram,
) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ByDjangoStructureService.getInstance(project).current() != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /**
     * Opens the diagram's file, `<provider>/<element>` in the Diagrams plugin's file system, which its
     * editor builds the diagram from as it does a diagram reopened with the project. The plugin's own
     * *Show Diagram* finds its provider from an asynchronous copy of the data context, which the
     * element put in it here did not survive: it said no diagram was found (measured in 263.7307).
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = VirtualFileManager.getInstance().getFileSystem(UmlVirtualFileSystem.PROTOCOL)
            ?.findFileByPath("${ByDjangoDiagramProvider.ID}/${ByDjangoDiagramProvider.ALL}") ?: return
        OpenFileDescriptor(project, file).navigate(true)
        // a diagram opened by its file is drawn unlaid, every node at the origin (measured in
        // 263.7307); the plugin's own Show Diagram lays it out as it opens, so this does the same
        for (editor in FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<DiagramFileEditor>()) {
            editor.viewBuilder.queryUpdate().withDataReload().withPresentationUpdate().withRelayout().runAsync()
        }
    }
}
