package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.test.tree.ByTestEventsConverter
import dev.basedpython.pycharm.run.test.tree.ByTestLocator
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

/** Options for a test run; reuses common working dir / extra args / env handling. */
class ByTestOptions : ByCommonOptions() {
    private val pathsProp = string("").provideDelegate(this, "paths")
    var paths: String
        get() = pathsProp.getValue(this) ?: ""
        set(v) { pathsProp.setValue(this, v) }

    private val plainPytestProp = property(false).provideDelegate(this, "plainPytest")

    /**
     * Run `python -m pytest` in the project instead of `by run pytest`.
     *
     * For tests that are already `.py`: `by run` transpiles `.by` files into a temp directory and
     * runs pytest there, so it never sees them (`by build`: "Transpile all .by files"). The node
     * view collects both kinds and sets this when what it is running came from the plain half.
     */
    var plainPytest: Boolean
        get() = plainPytestProp.getValue(this)
        set(v) { plainPytestProp.setValue(this, v) }
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
        if (opts.plainPytest) {
            return PlainPytestCommandLineState(project, opts, environment) { handler, exec ->
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    FRAMEWORK_NAME, handler, testConsoleProperties(config, exec),
                )
            }.also { it.paths = opts.paths }
        }
        return object : ByCommandLineState(project, opts, environment) {
            // `by run pytest`, not `by test` — see [ByPytest].
            override val subcommand = "run"

            override fun buildSubcommandArgs(): List<String> = ByPytest.arguments(opts.paths)

            /**
             * Assembled here rather than in `createConsole` because the SM runner attaches the
             * console itself.
             *
             * [CommandLineState.execute] starts the process, *then* asks for a console, then
             * attaches that console to the process it started. Building the console with
             * `SMTestRunnerConnectionUtil.createAndAttachConsole(…, startProcess(), …)` therefore
             * started a second `by run pytest` — the whole suite ran twice — and left the console
             * attached to both, so every test appeared twice in the tree while the Stop button
             * killed only one of the two processes.
             */
            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                val handler = startProcess()
                val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                    FRAMEWORK_NAME, handler, testConsoleProperties(config, executor),
                )
                return DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
            }
        }
    }

    /** Wires the `by`-specific output parser and source locator into the SM test runner. */
    private fun testConsoleProperties(
        config: ByTestConfiguration,
        executor: Executor,
    ): SMTRunnerConsoleProperties =
        object : SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
            override fun createTestEventsConverter(
                testFrameworkName: String,
                consoleProperties: TestConsoleProperties,
            ): OutputToGeneralTestEventsConverter =
                ByTestEventsConverter(testFrameworkName, consoleProperties)

            override fun getTestLocator(): SMTestLocator = ByTestLocator
        }

    private companion object {
        const val FRAMEWORK_NAME = "pytest (by)"
    }
}
