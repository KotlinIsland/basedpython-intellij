package dev.basedpython.pycharm.console

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.ui.log.BasedPythonLogNotifications
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Action `basedpython.OpenRepl`: open an interactive console running the `by`
 * binary (`by repl`, falling back to `by run`).
 *
 * Process resolution + start happen off the EDT (inside a background task);
 * the console UI is then shown back on the EDT. When the `by` binary cannot be
 * resolved the user is notified and pointed at the settings page.
 */
class OpenBasedPythonReplAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = isEnabled(e.project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openRepl(project)
    }

    /** Enablement: we need a project with a base path to anchor the working dir. */
    fun isEnabled(project: Project?): Boolean = project != null && project.basePath != null

    /**
     * Resolve the binary and launch the console. Public so it can be driven from
     * tests / other entry points. Safe to call on any thread.
     */
    fun openRepl(project: Project) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, BasedPythonBundle.message("repl.starting"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val launch = BasedPythonBinaries.launchBy(project)
                    if (launch == null) {
                        notifyMissing(project)
                        return
                    }
                    val workDir = project.basePath?.let { Paths.get(it) }
                    ApplicationManager.getApplication().invokeLater {
                        startConsole(project, launch, workDir)
                    }
                }
            },
        )
    }

    private fun startConsole(project: Project, launch: ByLaunch, workDir: Path?) {
        val settings = BasedPythonSettings.getInstance(project)
        val subcommand = replSubcommand()
        val extraArgs = settings.effectiveByExtraArgs

        val handler = try {
            val cmd = ByReplCommandLine.build(launch, subcommand, extraArgs, workDir)
            KillableProcessHandler(cmd)
        } catch (t: Throwable) {
            notifyStartFailed(project, t)
            return
        }
        ProcessTerminatedListener.attach(handler)

        val title = BasedPythonBundle.message("repl.consoleTitle")
        RunContentExecutor(project, handler)
            .withTitle(title)
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
            .run()
    }

    /** The subcommand to launch. Configurable hook; defaults to `repl`. */
    fun replSubcommand(): String = ByReplCommandLine.DEFAULT_SUBCOMMAND

    private fun notifyMissing(project: Project) {
        BasedPythonLogNotifications.create(
            project,
            BasedPythonBundle.message("repl.consoleTitle"),
            BasedPythonBundle.message("repl.binaryMissing"),
            NotificationType.WARNING,
        ).addAction(BasedPythonLogNotifications.openSettings(project))
            .notify(project)
    }

    private fun notifyStartFailed(project: Project, t: Throwable) {
        BasedPythonLogNotifications.create(
            project,
            BasedPythonBundle.message("repl.startFailed.title"),
            t.message ?: t.javaClass.simpleName,
            NotificationType.ERROR,
        ).notify(project)
    }
}
