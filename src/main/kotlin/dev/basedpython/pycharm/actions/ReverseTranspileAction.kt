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

/** Right-click a `.py` file → `by transpile --reverse <file>` → open as scratch `.by`. */
class ReverseTranspileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory && isPyFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("progress.reverseTranspiling", file.name), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val basedPython = ByTranspile.sourceOrNotify(
                    project,
                    file,
                    reverse = true,
                    failureTitle = BasedPythonBundle.message("notification.reverseTranspileFailed.title"),
                ) ?: return
                val byName = file.nameWithoutExtension + ".by"
                ApplicationManager.getApplication().invokeLater {
                    val scratch = LightVirtualFile(byName, BasedPythonFileType.INSTANCE, basedPython)
                    FileEditorManager.getInstance(project).openFile(scratch, true)
                }
            }
        })
    }

    private fun isPyFile(file: VirtualFile): Boolean = file.extension.equals("py", ignoreCase = true)
}
