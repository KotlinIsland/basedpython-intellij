package dev.basedpython.pycharm.actions

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
import com.intellij.openapi.util.IconLoader
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

        val bin = BasedPythonBinaries.resolveBy(project)
        if (bin == null) {
            ByCli.notifyBinaryMissing(project, "by")
            return
        }

        ApplicationManager.getApplication().invokeLater {
            startConsole(project, bin.toString(), basePath)
        }
    }

    private fun startConsole(project: Project, byExe: String, cwd: String) {
        val cmd = GeneralCommandLine()
            .withExePath(byExe)
            .withParameters("check")
            .withWorkDirectory(Paths.get(cwd).toFile())
            .withCharset(Charsets.UTF_8)

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
            IconLoader.getIcon("/icons/basedpython.svg", CheckProjectAction::class.java),
        )

        RunContentManager.getInstance(project)
            .showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        handler.startNotify()
    }
}
