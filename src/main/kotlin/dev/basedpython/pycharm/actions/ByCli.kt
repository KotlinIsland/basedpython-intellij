package dev.basedpython.pycharm.actions

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path

/**
 * Thin wrapper around the `by` and `buff` CLIs.
 *
 * Callers are responsible for off-EDT execution (e.g. wrap in [com.intellij.openapi.progress.Task.Backgroundable]).
 */
internal object ByCli {

    const val NOTIFICATION_GROUP_ID: String = "BasedPython.Actions"

    /** Run `by` with [args]. Returns `null` if the binary cannot be located. */
    fun run(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        @Suppress("UNUSED_PARAMETER") title: String = "by",
    ): ProcessOutput? {
        val bin = BasedPythonBinaries.resolveBy(project)
        if (bin == null) {
            notifyBinaryMissing(project, "by")
            return null
        }
        return exec(bin, args.toList(), cwd)
    }

    /** Run `buff` with [args]. Returns `null` if the binary cannot be located. */
    fun runBuff(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        @Suppress("UNUSED_PARAMETER") title: String = "buff",
    ): ProcessOutput? {
        val bin = BasedPythonBinaries.resolveBuff(project)
        if (bin == null) {
            notifyBinaryMissing(project, "buff")
            return null
        }
        return exec(bin, args.toList(), cwd)
    }

    private fun exec(bin: Path, args: List<String>, cwd: Path?): ProcessOutput {
        val cmd = GeneralCommandLine()
            .withExePath(bin.toString())
            .withParameters(args)
            .withCharset(Charsets.UTF_8)
        if (cwd != null) cmd.withWorkDirectory(cwd.toFile())
        return ExecUtil.execAndGetOutput(cmd)
    }

    fun notifyBinaryMissing(project: Project, name: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                BasedPythonBundle.message("notification.binaryMissing.title", name),
                BasedPythonBundle.message("notification.binaryMissing.content", name),
                NotificationType.WARNING,
            )
            .notify(project)
    }

    fun notifyInfo(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    fun notifyError(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }
}
