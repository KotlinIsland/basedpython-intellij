package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path

/**
 * Runs `uv sync` at the project base. Enabled only when a `uv` executable is on PATH or a
 * `uv.lock` / `pyproject.toml` exists at the base (see [UvSupport.canSync]).
 *
 * Output is surfaced via a lightweight notification carrying the exit code; the process runs
 * through an [OSProcessHandler] so we don't pull in a full console for a one-shot sync.
 */
class UvSyncAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && UvSupport.canSync(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val base: Path = UvSupport.basePath(project) ?: run {
            UvSupport.notify(project, BasedPythonBundle.message("uv.sync.title"), BasedPythonBundle.message("uv.noBasePath"), NotificationType.WARNING)
            return
        }
        val uv = UvSupport.findUv()

        val cmd = GeneralCommandLine()
            .withExePath(uv?.toString() ?: "uv")
            .withParameters("sync")
            .withWorkDirectory(base.toFile())
            .withCharset(Charsets.UTF_8)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val handler = OSProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        val code = event.exitCode
                        if (code == 0) {
                            UvSupport.notify(project, BasedPythonBundle.message("uv.sync.title"), BasedPythonBundle.message("uv.sync.success"), NotificationType.INFORMATION)
                        } else {
                            UvSupport.notify(project, BasedPythonBundle.message("uv.sync.title"), BasedPythonBundle.message("uv.sync.exitCode", code), NotificationType.ERROR)
                        }
                    }
                })
                handler.startNotify()
            } catch (ex: Exception) {
                UvSupport.notify(project, BasedPythonBundle.message("uv.sync.title"), BasedPythonBundle.message("uv.sync.startFailed", ex.message ?: ""), NotificationType.ERROR)
            }
        }
    }
}
