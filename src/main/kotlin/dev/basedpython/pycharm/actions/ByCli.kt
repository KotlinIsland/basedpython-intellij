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
        timeoutMs: Int? = null,
        @Suppress("UNUSED_PARAMETER") title: String = "by",
    ): ProcessOutput? {
        val launch = BasedPythonBinaries.launchBy(project, contextFile)
        if (launch == null) {
            notifyBinaryMissing(project, "by")
            return null
        }
        return exec(launch, args.toList(), cwd, timeoutMs)
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
        return exec(launch, args.toList(), cwd, timeoutMs = null)
    }

    /**
     * Runs [launch] with [args]. A [timeoutMs] kills the process when it elapses and comes back
     * with whatever was printed until then and [ProcessOutput.isTimeout] set; without one the call
     * waits forever, which is right for a command that only reads (`transpile`, `explain`) and
     * wrong for one that executes the user's code.
     */
    private fun exec(launch: ByLaunch, args: List<String>, cwd: Path?, timeoutMs: Int?): ProcessOutput {
        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            .withParameters(launch.prependArgs)
            .withParameters(args)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(launch.env)
        if (cwd != null) cmd.withWorkDirectory(cwd.toFile())
        return if (timeoutMs == null) ExecUtil.execAndGetOutput(cmd)
        else ExecUtil.execAndGetOutput(cmd, timeoutMs)
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

    fun notifyWarning(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }

    fun notifyError(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }
}
