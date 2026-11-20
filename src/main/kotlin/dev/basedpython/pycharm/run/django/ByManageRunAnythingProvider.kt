package dev.basedpython.pycharm.run.django

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.ide.actions.runAnything.RunAnythingUtil
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommand
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * `manage.py …` in Run Anything (double Ctrl, or *Tools | Run manage.py Task…*): a django command run
 * as a `by run` of the project's `manage.py` ([ByDjangoManage]), with Shift for the debugger, in the
 * Run tool window under the command's name, as PyCharm Professional's *Run manage.py Task* does.
 *
 * Completes the command's name from every command `manage.py` runs — django's own and each installed
 * app's, the project's included — as `by/djangoStructure` lists them, never a scan of the files or a
 * run of `manage.py help`. A command `by` does not list still runs: whatever is typed is handed to
 * `manage.py`, which says itself what it does not know.
 */
internal class ByManageRunAnythingProvider : RunAnythingCommandLineProvider() {

    override fun getHelpCommand(): String = MANAGE

    override fun getHelpCommandPlaceholder(): String = "manage.py <command> <arguments>"

    override fun getHelpGroupTitle(): String = BasedPythonBundle.message("django.manage.group")

    override fun getCompletionGroupTitle(): String = BasedPythonBundle.message("django.manage.group")

    override fun getHelpIcon(): Icon = BasedPythonIcons.Logo

    override fun getIcon(value: String): Icon = BasedPythonIcons.Logo

    override fun getHelpDescription(): String = BasedPythonBundle.message("django.manage.help")

    override fun suggestCompletionVariants(dataContext: DataContext, commandLine: CommandLine): Sequence<String> {
        val project = RunAnythingUtil.fetchProject(dataContext)
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return emptySequence()
        if (commandLine.completedParameters.isNotEmpty()) return emptySequence()
        return commands(project).mapNotNull { it.name }.distinct().sorted().asSequence()
    }

    override fun run(dataContext: DataContext, commandLine: CommandLine): Boolean {
        val project = RunAnythingUtil.fetchProject(dataContext)
        val arguments = commandLine.parameters
        if (arguments.isEmpty()) return false
        val manage = ByDjangoManage.find(project) ?: return false
        val name = "manage.py " + ParametersListUtil.join(arguments)
        val settings = ByDjangoManage.configuration(project, manage, BasedPythonRunConfigurationType.getInstance().runFactory, name, arguments)
        val runManager = RunManager.getInstance(project)
        runManager.setTemporaryConfiguration(settings)
        runManager.selectedConfiguration = settings
        val executor = dataContext.getData(RunAnythingAction.EXECUTOR_KEY) ?: DefaultRunExecutor.getRunExecutorInstance()
        ProgramRunnerUtil.executeConfiguration(settings, executor)
        return true
    }

    private fun commands(project: Project): List<ByDjangoCommand> {
        val service = ByDjangoStructureService.getInstance(project)
        val structure = service.fresh() ?: service.current()
        return structure?.projects.orEmpty().flatMap { it.commands }
    }

    companion object {
        const val MANAGE = "manage.py"
    }
}
