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
        val path = file.toNioPath()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reverse-transpiling ${file.name}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val out = ByCli.run(project, "transpile", "--reverse", path.toString(), cwd = path.parent) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(project, "by transpile --reverse failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                    return
                }
                val byName = file.nameWithoutExtension + ".by"
                ApplicationManager.getApplication().invokeLater {
                    val scratch = LightVirtualFile(byName, BasedPythonFileType.INSTANCE, out.stdout)
                    FileEditorManager.getInstance(project).openFile(scratch, true)
                }
            }
        })
    }

    private fun isPyFile(file: VirtualFile): Boolean = file.extension.equals("py", ignoreCase = true)
}
