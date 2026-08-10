package dev.basedpython.pycharm.actions

import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path

/**
 * Thin wrapper around the `by` and `buff` CLIs.
 *
 * Callers are responsible for off-EDT execution (e.g. wrap in [com.intellij.openapi.progress.Task.Backgroundable]).
 */
internal object ByCli {

    const val NOTIFICATION_GROUP_ID: String = "basedpython.Actions"

    /**
     * Run `by` with [args]. Returns `null` if the binary cannot be located.
     *
     * [contextFile] (when known) makes binary resolution content-root-aware so a per-module
     * `.venv` is preferred over the workspace-level one in a multi-root project.
     */
    fun run(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        contextFile: VirtualFile? = null,
        @Suppress("UNUSED_PARAMETER") title: String = "by",
    ): ProcessOutput? {
        val launch = BasedPythonBinaries.launchBy(project, contextFile)
        if (launch == null) {
            notifyBinaryMissing(project, "by")
            return null
        }
        return exec(launch, args.toList(), cwd)
    }

    /** Run `buff` with [args]. Returns `null` if the binary cannot be located. */
    fun runBuff(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        contextFile: VirtualFile? = null,
        @Suppress("UNUSED_PARAMETER") title: String = "buff",
    ): ProcessOutput? {
        val launch = BasedPythonBinaries.launchBuff(project, contextFile)
        if (launch == null) {
            notifyBinaryMissing(project, "buff")
            return null
        }
        return exec(launch, args.toList(), cwd)
    }

    private fun exec(launch: ByLaunch, args: List<String>, cwd: Path?): ProcessOutput {
        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            .withParameters(launch.prependArgs)
            .withParameters(args)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(launch.env)
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
