package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.main.ByMissingArgumentsHint
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

class ByRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByRunOptions>(project, factory, name) {

    public override fun getOptions(): ByRunOptions = super.getOptions() as ByRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByRunOptions>> =
        ByRunSettingsEditor(project)

    override fun checkConfiguration() {
        if (options.module.isBlank()) {
            throw RuntimeConfigurationException("Module is required (e.g. mypkg.main)")
        }
        if (!BasedPythonBinaries.isByAvailable(project)) {
            throw RuntimeConfigurationException("by binary not found — set path in Settings | basedpython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        val hint = ByMissingArgumentsHint(this, environment)
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "run"

            override fun buildSubcommandArgs(): List<String> = runSubcommandArgs(opts)

            // The console is created after the process, so the hint is told about each as it
            // appears; it needs the process to read the failure and the console to answer it.
            override fun startProcess(): ProcessHandler = super.startProcess().also(hint::watch)

            override fun createConsole(executor: Executor): ConsoleView? =
                super.createConsole(executor)?.also(hint::show)
        }
    }
}

/**
 * What follows `by run`: the module, then the program's own arguments.
 *
 * The order is the CLI's: `by run` takes exactly one positional — the module — and forwards
 * everything after it to the program as `sys.argv[1:]`, which for a module with a `main` function
 * is that function's parameters. Splitting is shell-like, so `--name "two words"` is two arguments.
 */
internal fun runSubcommandArgs(options: ByRunOptions): List<String> =
    listOf(options.module.trim()) + ParametersListUtil.parse(options.programArgs)
