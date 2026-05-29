package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.nio.file.Paths

/** `buff clean` at project root. */
class CleanCachesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val cwd = Paths.get(basePath)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Cleaning buff caches", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val out = ByCli.runBuff(project, "clean", cwd = cwd) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(project, "buff clean failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                    return
                }
                ByCli.notifyInfo(project, "BasedPython", "buff caches cleaned")
            }
        })
    }
}
