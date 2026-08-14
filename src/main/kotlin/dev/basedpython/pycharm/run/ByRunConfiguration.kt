package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.main.ByMainArguments
import dev.basedpython.pycharm.run.main.ByMainModules
import dev.basedpython.pycharm.run.main.ByMissingArgumentsHint
import dev.basedpython.pycharm.run.main.promptForArguments
import com.intellij.openapi.application.ApplicationManager
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

    /**
     * Null when the user cancelled the argument prompt: [com.intellij.execution.runners.ProgramRunner]
     * takes that as "this run cannot start" and stops, quietly, which is what cancelling a dialog
     * should do.
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        if (!askForArguments(executor)) return null
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

    /**
     * Asks for the arguments this run needs and cannot start without, and returns whether it may
     * go ahead.
     *
     * Picking Run on a `def main(a: int)` should produce the question, not the answer argparse
     * would give — `error: the following arguments are required: a`, exit 2, nothing run. So the
     * configuration asks here, at the one moment it is certain a run is actually starting: this is
     * the executing path only, unlike `checkSettingsBeforeRun`, which the platform also calls to
     * decide whether a configuration's icon should look broken.
     *
     * It asks *only* when the run would otherwise fail on the spot. Arguments already given — by
     * the form, by hand, or seeded from the last run of the same module — start without a word,
     * which is what keeps this a question about the program rather than a step in every run.
     */
    private fun askForArguments(executor: Executor): Boolean {
        val application = ApplicationManager.getApplication()
        // Nothing can answer a dialog in these, and a run that hangs on one is worse than a run
        // that fails with the error the program itself would print.
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return true
        val main = ByMainModules.mainFor(project, options.module)
        if (!ByMainArguments.needed(main, options.programArgs)) return true

        var proceed = false
        // `getState` is the platform's, not ours, and which thread it arrives on has changed
        // before; `invokeAndWait` runs inline when it is already the EDT and blocks the run until
        // the dialog closes when it is not — either way the answer is written before the command
        // line is built from it.
        application.invokeAndWait {
            proceed = promptForArguments(project, this, main!!, start = executor.actionName) != null
        }
        return proceed
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
