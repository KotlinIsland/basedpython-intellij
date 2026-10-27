package dev.basedpython.pycharm.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.actions.runAnything.RunAnythingAction
import com.intellij.ide.actions.runAnything.RunAnythingContext
import com.intellij.ide.actions.runAnything.getPath
import com.intellij.ide.actions.runAnything.RunAnythingUtil
import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.ide.actions.runAnything.activity.RunAnythingProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.console.ByConsoles
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByTestItem
import dev.basedpython.pycharm.run.test.ByTestOptions
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * `by …` in Run Anything (double Ctrl): `by run`, `by check`, `by build` and `by repl`, as `.py`'s
 * `python main.py` is — a run configuration made and run, with Shift for the debugger.
 *
 * Completes the subcommand, then for `by run` the modules of the project by the names `by run` runs
 * them under, and `pytest`; for `by run pytest` the tests pytest would collect, by the node ids a test
 * configuration runs them by. Both are `by`'s answers through [ByProgramModel] — `by/runModules` and
 * `by/testItems` — never a scan of the files.
 *
 * What runs: `by run <module> <args>…` a `by run` configuration of that module with those program
 * arguments; flags before the module, written `--flag=value`, are its `by` options. `by run pytest
 * <targets>…` a basedpython test configuration of those targets, whose tests show in the test tree.
 * `by check <paths>…` and `by build <flags>…` their configurations, and `by repl` the basedpython
 * console. Each configuration is the run's temporary one, as Run Anything's are.
 */
internal class ByRunAnythingProvider : RunAnythingCommandLineProvider() {

    override fun getHelpCommand(): String = BY

    override fun getHelpCommandPlaceholder(): String = "by run|check|build|repl <arguments>"

    override fun getHelpGroupTitle(): String = BasedPythonBundle.message("runAnything.group")

    override fun getCompletionGroupTitle(): String = BasedPythonBundle.message("runAnything.group")

    override fun getHelpIcon(): Icon = BasedPythonIcons.Logo

    override fun getIcon(value: String): Icon = BasedPythonIcons.Logo

    override fun getHelpDescription(): String = BasedPythonBundle.message("runAnything.help")

    override fun suggestCompletionVariants(dataContext: DataContext, commandLine: CommandLine): Sequence<String> {
        val project = RunAnythingUtil.fetchProject(dataContext)
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return emptySequence()
        val completed = commandLine.completedParameters
        val model = ByProgramModel.getInstance(project)
        return when {
            completed.isEmpty() -> SUBCOMMANDS.asSequence()
            completed == listOf(RUN) -> (model.moduleNames().orEmpty().sorted() + PYTEST).asSequence()
            completed.size >= 2 && completed[0] == RUN && completed[1] == PYTEST -> testTargets(model).asSequence()
            else -> emptySequence()
        }
    }

    override fun run(dataContext: DataContext, commandLine: CommandLine): Boolean {
        val project = RunAnythingUtil.fetchProject(dataContext)
        val parameters = commandLine.parameters
        val subcommand = parameters.firstOrNull() ?: return false
        val rest = parameters.drop(1)
        if (subcommand == REPL) {
            ByConsoles.getInstance(project).show()
            return true
        }
        val directory = workingDirectory(dataContext, project)
        val type = BasedPythonRunConfigurationType.getInstance()
        val name = "by " + ParametersListUtil.join(parameters)
        val settings = when (subcommand) {
            RUN -> if (rest.firstOrNull { !it.startsWith("-") } == PYTEST) {
                testConfiguration(project, name, rest, directory) ?: return false
            } else {
                configuration(project, name, type.runFactory) { options: ByRunOptions ->
                    val (flags, positionals) = byRunParts(rest)
                    options.extraArgs = ParametersListUtil.join(flags)
                    options.module = positionals.firstOrNull().orEmpty()
                    options.programArgs = ParametersListUtil.join(positionals.drop(1))
                    options.workingDir = directory
                }
            }
            CHECK -> configuration(project, name, type.checkFactory) { options: ByCheckOptions ->
                options.paths = ParametersListUtil.join(rest)
                options.workingDir = directory
            }
            BUILD -> configuration(project, name, type.buildFactory) { options: ByBuildOptions ->
                options.extraArgs = ParametersListUtil.join(rest)
                options.workingDir = directory
            }
            else -> return false
        }
        val runManager = RunManager.getInstance(project)
        runManager.setTemporaryConfiguration(settings)
        runManager.selectedConfiguration = settings
        val executor = dataContext.getData(RunAnythingAction.EXECUTOR_KEY) ?: DefaultRunExecutor.getRunExecutorInstance()
        ProgramRunnerUtil.executeConfiguration(settings, executor)
        return true
    }

    /** A `by run pytest` of [rest]'s targets as a test configuration, when the test runner is there to show it. */
    private fun testConfiguration(project: Project, name: String, rest: List<String>, directory: String) =
        ConfigurationTypeUtil.findConfigurationType(TEST_TYPE_ID)?.configurationFactories?.firstOrNull()?.let { factory ->
            configuration(project, name, factory) { options: ByTestOptions ->
                options.paths = ParametersListUtil.join(rest.dropWhile { it != PYTEST }.drop(1))
                options.workingDir = directory
            }
        }

    private inline fun <reified O> configuration(
        project: Project,
        name: String,
        factory: ConfigurationFactory,
        configure: (O) -> Unit,
    ) = RunManager.getInstance(project).createConfiguration(name, factory).also { settings ->
        val options = (settings.configuration as ByConfiguration).getOptions()
        configure(options as O)
    }

    /** The directory Run Anything's context names, or the project's when it names the project. */
    private fun workingDirectory(dataContext: DataContext, project: Project): String {
        val context = dataContext.getData(RunAnythingProvider.EXECUTING_CONTEXT) ?: return ""
        if (context is RunAnythingContext.ProjectContext) return ""
        return context.getPath()?.takeIf { it != project.basePath }.orEmpty()
    }

    private fun testTargets(model: ByProgramModel): List<String> =
        model.projectTests()?.byFile.orEmpty().flatMap { (file, tests) ->
            val staged = model.stagedPath(file) ?: return@flatMap emptyList()
            listOf(staged) + tests.flatMap { it.nodeIds(staged) }
        }.sorted()

    private fun ByTestItem.nodeIds(staged: String): List<String> =
        listOf(staged + "::" + symbols.joinToString("::")) + children.flatMap { it.nodeIds(staged) }

    internal companion object {
        const val BY = "by"
        const val RUN = "run"
        const val CHECK = "check"
        const val BUILD = "build"
        const val REPL = "repl"
        const val PYTEST = "pytest"
        val SUBCOMMANDS = listOf(RUN, CHECK, BUILD, REPL)

        /** [ByTestConfigurationType]'s id, named rather than its class: that one exists only with the test runner. */
        const val TEST_TYPE_ID = "BasedPythonTestRunConfiguration"

        /**
         * What follows `by run`, as `by`'s flags and then the module and its arguments: every token
         * before the first that is not a flag is one of `by`'s, so a flag's value is written
         * `--flag=value`; everything from the module on is the program's.
         */
        fun byRunParts(arguments: List<String>): Pair<List<String>, List<String>> {
            val flags = arguments.takeWhile { it.startsWith("-") }
            return flags to arguments.drop(flags.size)
        }
    }
}
