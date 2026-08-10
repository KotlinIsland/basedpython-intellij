package dev.basedpython.pycharm.debug

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.env.UvSupport
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path
import java.nio.file.Paths

/** The package the debuggee bootstrap needs; see [ByDebugAdapter]. */
private const val DEBUGPY = "debugpy"

/**
 * How to put `debugpy` where the debugger will find it.
 *
 * "Where" is not a matter of taste: the bootstrap runs inside whichever interpreter `by run`
 * chose, and that is the only one that counts. [python] is what that process reported as its own
 * `sys.executable`, so installing into it cannot miss.
 */
sealed interface ByDebugpyInstall {
    /** `uv add --dev debugpy`, for a project uv already manages — keeps `pyproject.toml` honest. */
    data class WithUv(val uv: Path, val projectDir: Path) : ByDebugpyInstall

    /** `<interpreter> -m pip install debugpy`, for everything else. */
    data class WithPip(val python: Path) : ByDebugpyInstall

    /** The command to run, as a plain list so it can be asserted without building a process. */
    val arguments: List<String>
        get() = when (this) {
            is WithUv -> listOf(uv.toString(), "add", "--dev", DEBUGPY)
            is WithPip -> listOf(python.toString(), "-m", "pip", "install", DEBUGPY)
        }

    val workingDir: Path?
        get() = when (this) {
            is WithUv -> projectDir
            is WithPip -> null
        }

    /** The command as the user would type it, for the notification text. */
    fun describe(): String = arguments.joinToString(" ")

    val commandLine: GeneralCommandLine
        get() = GeneralCommandLine(arguments)
            .withCharset(Charsets.UTF_8)
            .also { cmd -> workingDir?.let { cmd.withWorkDirectory(it.toFile()) } }

    companion object {
        fun plan(project: Project, python: String?): ByDebugpyInstall? = choose(
            uv = UvSupport.findUv(),
            projectDir = UvSupport.basePath(project),
            isUvProject = UvSupport.hasProjectMarker(project),
            python = python,
        )

        /**
         * uv when the project is already a uv project *and* uv is on the machine, else pip into
         * the interpreter that reported the failure.
         *
         * uv is preferred there for the same reason the missing-`by` banner prefers it: in a uv
         * project a bare `pip install` writes into an environment uv will later rebuild from the
         * lock file, and the package quietly disappears again. Everywhere else uv would be an
         * unwanted side effect — it creates environments and downloads interpreters — so pip it is,
         * aimed at the exact `sys.executable` the debuggee reported rather than at a guess.
         */
        fun choose(
            uv: Path?,
            projectDir: Path?,
            isUvProject: Boolean,
            python: String?,
        ): ByDebugpyInstall? {
            if (uv != null && projectDir != null && isUvProject) return WithUv(uv, projectDir)
            val interpreter = python?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return WithPip(runCatching { Paths.get(interpreter) }.getOrNull() ?: return null)
        }
    }
}

/**
 * Reports a debug session that could not start, with a one-click way out.
 *
 * Deliberately a notification rather than a thrown message. The platform turns anything
 * [com.intellij.platform.dap.DebugAdapterDescriptor.launchDebugAdapter] throws into a
 * `DapInitializationException` and — unless it is a `CustomProcessedCantRunException` — rethrows it
 * out of a coroutine, where it lands as an "Unhandled exception" error box naming
 * `CoroutineScheduler` and `Rete`. A missing package is an ordinary, fixable situation and should
 * not look like an IDE crash, so the reporting happens here and the throw is silenced.
 */
internal fun reportDebugStartFailure(project: Project, message: String, install: ByDebugpyInstall?) {
    val content = if (install == null) message else {
        message + "\n" + BasedPythonBundle.message("debug.error.fixWith", install.describe())
    }
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(ByCli.NOTIFICATION_GROUP_ID)
        .createNotification(
            BasedPythonBundle.message("debug.error.title"),
            content,
            NotificationType.ERROR,
        )
    if (install != null) {
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                BasedPythonBundle.message("debug.action.installDebugpy"),
            ) { runInstall(project, install) },
        )
    }
    notification.notify(project)
}

private fun runInstall(project: Project, install: ByDebugpyInstall) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val handler = OSProcessHandler(install.commandLine.withCharset(Charsets.UTF_8))
            ProcessTerminatedListener.attach(handler)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    if (event.exitCode == 0) {
                        ByCli.notifyInfo(
                            project,
                            BasedPythonBundle.message("debug.install.title"),
                            BasedPythonBundle.message("debug.install.success"),
                        )
                    } else {
                        ByCli.notifyError(
                            project,
                            BasedPythonBundle.message("debug.install.title"),
                            BasedPythonBundle.message("debug.install.failed", install.describe(), event.exitCode),
                        )
                    }
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            ByCli.notifyError(
                project,
                BasedPythonBundle.message("debug.install.title"),
                BasedPythonBundle.message("debug.install.startFailed", install.describe(), e.message ?: ""),
            )
        }
    }
}
