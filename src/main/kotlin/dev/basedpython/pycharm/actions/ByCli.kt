package dev.basedpython.pycharm.actions

import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import dev.basedpython.pycharm.run.runCapturing
import kotlin.time.Duration.Companion.milliseconds
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
/** One `by` invocation: what was run, and what came back. */
internal data class ByExecution(
    val commandLine: String,
    val workingDirectory: String?,
    val output: ProcessOutput,
)

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
    ): ProcessOutput? = runDetailed(
        project,
        args = args,
        cwd = cwd,
        contextFile = contextFile,
        timeoutMs = timeoutMs,
    )?.output

    /**
     * [run], plus the command line it resolved to.
     *
     * For callers that have to *show* what ran rather than only act on the result: `by` is found by
     * a search order (venv, uv, interpreter, `PATH`) and may be launched through `uv run`, so the
     * command is not something a caller can reconstruct — and it is the first thing worth seeing
     * when a run behaves differently from the same command typed into a terminal.
     */
    fun runDetailed(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        contextFile: VirtualFile? = null,
        timeoutMs: Int? = null,
    ): ByExecution? {
        val launch = BasedPythonBinaries.launchBy(project, contextFile)
        if (launch == null) {
            notifyBinaryMissing(project, "by")
            return null
        }
        val command = commandLine(launch, args.toList(), cwd)
        return ByExecution(
            commandLine = command.commandLineString,
            workingDirectory = command.workDirectory?.path,
            output = execute(command, timeoutMs),
        )
    }

    /**
     * The `by` command [runDetailed] would run, for a caller that runs it itself — under its own
     * cancellation, say. Null, having said so, when the binary cannot be located.
     */
    fun commandLine(
        project: Project,
        vararg args: String,
        cwd: Path? = null,
        contextFile: VirtualFile? = null,
    ): GeneralCommandLine? {
        val launch = BasedPythonBinaries.launchBy(project, contextFile)
        if (launch == null) {
            notifyBinaryMissing(project, "by")
            return null
        }
        return commandLine(launch, args.toList(), cwd)
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

    private fun exec(launch: ByLaunch, args: List<String>, cwd: Path?, timeoutMs: Int?): ProcessOutput =
        execute(commandLine(launch, args, cwd), timeoutMs)

    /** The command [launch] and [args] amount to, in [cwd]. */
    private fun commandLine(launch: ByLaunch, args: List<String>, cwd: Path?): GeneralCommandLine {
        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            .withParameters(launch.prependArgs)
            .withParameters(args)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(launch.env)
        if (cwd != null) cmd.withWorkDirectory(cwd.toFile())
        return cmd
    }

    /**
     * Runs [cmd]. A [timeoutMs] kills the process when it elapses and comes back with whatever was
     * printed until then and [ProcessOutput.isTimeout] set; without one the call waits for the
     * process to end.
     *
     * Either way it can be cancelled: under a progress indicator, pressing Cancel kills the process
     * (and what it started) and the cancellation is rethrown, where `ExecUtil.execAndGetOutput`
     * would have gone on waiting for a process nobody could stop.
     */
    internal fun execute(cmd: GeneralCommandLine, timeoutMs: Int?): ProcessOutput =
        runBlockingMaybeCancellable { runCapturing(cmd, timeoutMs?.milliseconds) }

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
