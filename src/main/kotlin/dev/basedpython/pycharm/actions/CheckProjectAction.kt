package dev.basedpython.pycharm.actions

import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.BasedPythonIcons
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/** `by check` at project root, output to a Run tool window console. */
class CheckProjectAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        val launch = BasedPythonBinaries.launchBy(project)
        if (launch == null) {
            ByCli.notifyBinaryMissing(project, "by")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            startConsole(project, launch, basePath)
        }
    }

    private fun startConsole(project: Project, launch: ByLaunch, cwd: String) {
        val cmd = GeneralCommandLine()
            .withExePath(launch.exe.toString())
            // Must precede "check": for a uv launch the exe is `uv`, so without the prefix this
            // would run `uv check` — a different tool entirely, not the basedpython type checker.
            .withParameters(launch.prependArgs)
            .withParameters("check")
            .withWorkDirectory(Paths.get(cwd).toFile())
            .withCharset(Charsets.UTF_8)
            .withEnvironment(launch.env)

        val handler = ColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(handler)

        val console: ConsoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console
        console.attachToProcess(handler)
        console.print("> by check\n", ConsoleViewContentType.SYSTEM_OUTPUT)

        val panel = JPanel(BorderLayout()).apply {
            add(console.component, BorderLayout.CENTER)
        }
        val descriptor = RunContentDescriptor(
            console,
            handler,
            panel as JComponent,
            "by check",
            BasedPythonIcons.Logo,
        )

        RunContentManager.getInstance(project)
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        handler.startNotify()
    }
}
