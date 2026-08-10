package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.test.tree.ByTestEventsConverter
import dev.basedpython.pycharm.run.test.tree.ByTestLocator
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.execution.ParametersListUtil

/** Options for `by test`; reuses common working dir / extra args / env handling. */
class ByTestOptions : ByCommonOptions() {
    private val pathsProp = string("").provideDelegate(this, "paths")
    var paths: String
        get() = pathsProp.getValue(this) ?: ""
        set(v) { pathsProp.setValue(this, v) }
}

class ByTestConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<ByTestOptions>(project, factory, name) {

    public override fun getOptions(): ByTestOptions = super.getOptions() as ByTestOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByTestOptions>> =
        ByTestSettingsEditor()

    override fun checkConfiguration() {
        if (!BasedPythonBinaries.isByAvailable(project)) {
            throw RuntimeConfigurationException("by binary not found — set path in Settings | basedpython")
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val opts = options
        val config = this
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "test"

            // `by test` takes no version flag.
            override val pythonVersionFlag: String? = null

            override fun buildSubcommandArgs(): List<String> =
                if (opts.paths.isBlank()) emptyList() else ParametersListUtil.parse(opts.paths)

            override fun createConsole(executor: Executor): ConsoleView {
                val props = object : SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor),
                    SMCustomMessagesParsing {
                    override fun createTestEventsConverter(
                        testFrameworkName: String,
                        consoleProperties: TestConsoleProperties,
                    ): OutputToGeneralTestEventsConverter =
                        ByTestEventsConverter(testFrameworkName, consoleProperties)

                    override fun getTestLocator(): SMTestLocator = ByTestLocator
                }
                return SMTestRunnerConnectionUtil.createAndAttachConsole(FRAMEWORK_NAME, startProcess(), props)
            }
        }
    }

    private companion object {
        const val FRAMEWORK_NAME = "by test"
    }
}
