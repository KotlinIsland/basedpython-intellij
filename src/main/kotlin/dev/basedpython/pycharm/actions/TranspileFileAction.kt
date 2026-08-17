package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.transpile.ByTranspile
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Paths

/** Right-click a `.by` file → ask the server what it lowers to → open it in a scratch Python tab. */
class TranspileFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("progress.transpiling", file.name), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val python = ByTranspile.sourceOrNotify(
                    project,
                    file,
                    failureTitle = BasedPythonBundle.message("notification.transpileFailed.title"),
                ) ?: return
                val pyName = file.nameWithoutExtension + ".py"
                ApplicationManager.getApplication().invokeLater {
                    val scratch = LightVirtualFile(pyName, com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("py"), python)
                    FileEditorManager.getInstance(project).openFile(scratch, true)
                }
            }
        })
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)

    @Suppress("unused")
    private fun pathOf(file: VirtualFile) = Paths.get(file.path)
}
