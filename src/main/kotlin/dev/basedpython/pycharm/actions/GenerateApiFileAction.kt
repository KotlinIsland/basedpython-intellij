package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Paths

/** `by generate-api-file` at project root → refresh VFS → open api.lock if present. */
class GenerateApiFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val cwd = Paths.get(basePath)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating api.lock", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val out = ByCli.run(project, "generate-api-file", cwd = cwd) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(project, "by generate-api-file failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                    return
                }

                val apiLock = cwd.resolve("api.lock")
                val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(apiLock)
                if (refreshed != null) VfsUtil.markDirtyAndRefresh(false, false, false, refreshed)

                val ruleCount = parseRuleCount(out.stdout)
                val summary = buildString {
                    append("api.lock generated")
                    if (ruleCount != null) append(" — $ruleCount rule${if (ruleCount == 1) "" else "s"}")
                }
                ByCli.notifyInfo(project, "BasedPython", summary)

                if (refreshed != null) {
                    ApplicationManager.getApplication().invokeLater {
                        FileEditorManager.getInstance(project).openFile(refreshed, true)
                    }
                }
            }
        })
    }

    /** Best-effort: look for "N rules" in stdout. */
    private fun parseRuleCount(stdout: String): Int? =
        Regex("""(\d+)\s+rules?""", RegexOption.IGNORE_CASE).find(stdout)?.groupValues?.get(1)?.toIntOrNull()
}
