package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle

/** `buff format <file>` for `.by`/`.py` files. Bound to Ctrl+Alt+Shift+L. */
class FormatWithBuffAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory && isFormattable(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val path = file.toNioPath()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("progress.formattingWithBuff", file.name), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val out = ByCli.runBuff(project, "format", path.toString(), cwd = path.parent) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(project, BasedPythonBundle.message("notification.formatFailed.title"), out.stderr.ifBlank { BasedPythonBundle.message("notification.exitCode", out.exitCode) })
                    return
                }
                VfsUtil.markDirtyAndRefresh(true, false, false, file)
                ByCli.notifyInfo(project, BasedPythonBundle.message("notification.basedPython.title"), BasedPythonBundle.message("notification.formatted", file.name))
            }
        })
    }

    private fun isFormattable(file: VirtualFile): Boolean {
        if (file.fileType == BasedPythonFileType.INSTANCE) return true
        val ext = file.extension?.lowercase() ?: return false
        return ext == "by" || ext == "py"
    }
}
