package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.console.ByConsoleRunState

import dev.basedpython.pycharm.run.main.ByMainArguments
import dev.basedpython.pycharm.run.main.ByMissingArgumentsHint
import dev.basedpython.pycharm.run.main.promptForArguments
import dev.basedpython.pycharm.run.model.ByProgramModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.execution.CantRunException
import com.intellij.execution.EnvFilesOptions
import com.intellij.execution.ExecutionException
import com.intellij.execution.InputRedirectAware
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.util.ProgramParametersConfigurator
import com.intellij.execution.util.configureEnvsFromFiles
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.io.File

class ByRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    ByTargetAwareConfiguration<ByRunOptions>(project, factory, name), EnvFilesOptions, InputRedirectAware {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByRunOptions>> =
        ByRunFragmentedEditor(this)

    /** The platform's word for [ByRunOptions.envFilePaths], which its environment field edits. */
    override var envFilePaths: List<String>
        get() = options.envFilePaths
        set(value) { options.envFilePaths = value.toMutableList() }

    override fun getInputRedirectOptions(): InputRedirectAware.InputRedirectOptions = object : InputRedirectAware.InputRedirectOptions {
        override fun isRedirectInput(): Boolean = options.redirectInput
        override fun setRedirectInput(value: Boolean) {
            options.redirectInput = value
        }
        override fun getRedirectInputPath(): String = options.inputFile
        override fun setRedirectInputPath(value: String?) {
            options.inputFile = value.orEmpty()
        }
    }

    /**
     * The file [ByRunOptions.redirectInput] reads, its macros expanded, relative to the working
     * directory when it is not absolute; `null` when input is not redirected.
     */
    fun inputFile(): File? {
        val file = InputRedirectAware.getInputFile(this) ?: return null
        if (file.isAbsolute) return file
        return File(options.workingDir.ifBlank { project.basePath ?: return file }, file.path)
    }

    /**
     * A blank module is `by run` with no module, which runs the project's configured `run.main`.
     *
     * So it is only an error where `by run` would refuse it: when the server has said the project
     * configures no `run.main` — while it has said nothing yet, the run is let through to report
     * for itself — or when there are program arguments, because `by run` reads its first argument
     * as the module and there is no spelling that hands arguments to `run.main`.
     */
    override fun checkConfiguration() {
        if (options.module.isBlank()) {
            if (options.programArgs.isNotBlank()) {
                throw RuntimeConfigurationException(
                    "Name the module to pass program arguments: `by run` reads its first argument as the module",
                )
            }
            val main = ByProgramModel.getInstance(project).configuredMain()
            if (main.known && main.module == null) {
                throw RuntimeConfigurationException(
                    "Module is required: this project configures no `run.main` (e.g. mypkg.main)",
                )
            }
        }
        inputFile()?.let { file ->
            if (!file.isFile) throw RuntimeConfigurationException(BasedPythonBundle.message("run.error.inputFile", file.path))
        }
        checkEnvFiles()
        checkBy()
        if (options.runWithConsole) checkConsole()
    }

    /** What [ByRunOptions.runWithConsole] leaves out of a run, said before it starts rather than silently. */
    internal fun checkConsole() {
        if (targetName != null) throw RuntimeConfigurationError(BasedPythonBundle.message("run.error.consoleOnTarget"))
        if (options.programArgs.isNotBlank()) throw RuntimeConfigurationWarning(BasedPythonBundle.message("run.warning.consoleArguments"))
        if (options.emulateTerminal || options.redirectInput) throw RuntimeConfigurationWarning(BasedPythonBundle.message("run.warning.consoleTerminal"))
    }

    /**
     * Null when the user cancelled the argument prompt: [com.intellij.execution.runners.ProgramRunner]
     * takes that as "this run cannot start" and stops, quietly, which is what cancelling a dialog
     * should do.
     */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        refuseOnTarget(executor)
        // Run only: a debug session needs `by run`'s process to attach to, which a console has not got
        if (options.runWithConsole && executor.id == DefaultRunExecutor.EXECUTOR_ID) {
            if (targetName != null) throw CantRunException(BasedPythonBundle.message("run.error.consoleOnTarget"))
            return ByConsoleRunState(this)
        }
        if (!askForArguments(executor)) return null
        val opts = options
        val hint = ByMissingArgumentsHint(this, environment)
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "run"

            override fun buildSubcommandArgs(): List<String> = runSubcommandArgs(opts)

            override fun fileEnvironment(): Map<String, String> =
                try {
                    configureEnvsFromFiles(this@ByRunConfiguration)
                } catch (e: ProgramParametersConfigurator.ParametersConfiguratorException) {
                    throw ExecutionException(e.message, e)
                }

            override val inputFile: File? = inputFile()

            override val emulateTerminal: Boolean = opts.emulateTerminal

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
        val main = ByProgramModel.getInstance(project).mainForWaiting(options.module, READING_MAIN)
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

    /** Whether every `.env` file named can be read, as the platform checks a configuration's. */
    private fun checkEnvFiles() {
        try {
            configureEnvsFromFiles(this, parse = false)
        } catch (e: ProgramParametersConfigurator.ParametersConfiguratorException) {
            throw RuntimeConfigurationException(e.message)
        }
    }

    private companion object {
        const val READING_MAIN = "Reading main's parameters"
    }
}

/**
 * What follows `by run`: the module, then the program's own arguments.
 *
 * The order is the CLI's: `by run` takes one optional positional — the module, the project's
 * `run.main` when it is left out — and forwards everything after it to the program as
 * `sys.argv[1:]`, which for a module with a `main` function is that function's parameters. Splitting
 * is shell-like, so `--name "two words"` is two arguments.
 */
internal fun runSubcommandArgs(options: ByRunOptions): List<String> =
    listOfNotNull(options.module.trim().ifBlank { null }) + ParametersListUtil.parse(options.programArgs)
