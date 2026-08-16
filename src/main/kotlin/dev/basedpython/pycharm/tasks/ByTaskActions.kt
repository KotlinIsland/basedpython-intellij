package dev.basedpython.pycharm.tasks

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Paths

/** What the task view can do with a row: run it, and open the line that declares it. */
internal object ByTaskActions {

    /**
     * Runs [node] under [executor].
     *
     * As a temporary run configuration, which is what the gutter icons and context-menu Run produce
     * everywhere else in the IDE: it appears in the run combo box, can be edited or saved from
     * there, and is evicted once enough others accumulate. The alternative — starting a process
     * directly — would run the same command while giving the user nowhere to change it and no Rerun
     * button.
     */
    fun run(project: Project, node: ByTaskNode, executor: Executor): Boolean {
        val settings = configure(project, node) ?: return false
        RunManager.getInstance(project).setTemporaryConfiguration(settings)
        ProgramRunnerUtil.executeConfiguration(settings, executor)
        return true
    }

    /**
     * The run configuration for [node], or null when the node runs nothing.
     *
     * Separate from [run] so that what a row *would* run can be built and inspected without a
     * process starting — which is what the tests of this do, and the only honest way to test it.
     */
    fun configure(project: Project, node: ByTaskNode): RunnerAndConfigurationSettings? {
        if (ByTaskCommands.arguments(node, allFiles = false) == null) return null
        val settings = RunManager.getInstance(project).createConfiguration(
            name(node),
            ByTaskConfigurationType.getInstance().taskFactory,
        )
        val configuration = settings.configuration as ByTaskConfiguration
        val options = configuration.options
        options.runner = node.runner.id
        options.configPath = node.path
        options.taskKind = node.kind.name
        options.taskId = node.id.orEmpty()
        options.stage = node.stage.orEmpty()
        options.allFiles = ByTaskService.getInstance(project).allFiles &&
            ByTaskCommands.supportsAllFiles(node.runner)
        project.basePath?.let { options.workingDir = it }
        return settings
    }

    /**
     * What the run configuration is called.
     *
     * The runner's name is in there because a project can have hooks in three files and a `lint`
     * in two of them, and a run combo box holding two entries called `lint` helps nobody.
     */
    fun name(node: ByTaskNode): String = when (node.kind) {
        ByTaskKind.FILE -> BasedPythonBundle.message("tasks.run.name.all", node.runner.display)
        else -> BasedPythonBundle.message("tasks.run.name.task", node.runner.display, node.name)
    }

    /** Opens the configuration file at the line [node] was declared on; false when it is gone. */
    fun navigate(project: Project, node: ByTaskNode): Boolean {
        val base = project.basePath ?: return false
        val file = LocalFileSystem.getInstance().findFileByNioFile(Paths.get(base).resolve(node.path)) ?: return false
        OpenFileDescriptor(project, file, node.line, 0).navigate(true)
        return true
    }
}
