package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.ByTargetAwareConfiguration
import dev.basedpython.pycharm.run.ergonomics.ByConsoleFilter
import dev.basedpython.pycharm.run.test.tree.ByTargetTestLocator
import dev.basedpython.pycharm.util.BasedPythonBundle
import com.intellij.execution.CantRunException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import java.nio.file.Path
import dev.basedpython.pycharm.run.test.tree.ByTestEventsConverter
import dev.basedpython.pycharm.run.test.tree.ByDjangoTestEventsConverter
import dev.basedpython.pycharm.run.django.ByDjangoManage
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.run.test.tree.ByTestLocator
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.ui.ConsoleView
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

    private val runnerProp = string("").provideDelegate(this, "runner")

    /**
     * What runs [paths]: pytest, or django's `manage.py test` ([ByDjangoTests]). [paths] are pytest
     * node ids either way; a Django run names them to its runner as labels. [plainPytest] still says
     * whether `by run` or the project's interpreter starts it.
     */
    var runner: ByTestRunner
        get() = ByTestRunner.of(runnerProp.getValue(this)) ?: ByTestRunner.PYTEST
        set(v) { runnerProp.setValue(this, if (v == ByTestRunner.PYTEST) "" else v.id) }
}

class ByTestConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    ByTargetAwareConfiguration<ByTestOptions>(project, factory, name) {

    override fun getConfigurationEditor(): SettingsEditor<out RunConfigurationBase<ByTestOptions>> =
        ByTestSettingsEditor(project)

    override fun checkConfiguration() {
        checkBy()
        if (options.plainPytest && targetName != null) {
            throw RuntimeConfigurationError(BasedPythonBundle.message("target.error.plainPytest"))
        }
        if (options.runner == ByTestRunner.DJANGO && ByDjangoManage.find(project) == null) {
            throw RuntimeConfigurationError(BasedPythonBundle.message("django.tests.noManage"))
        }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        refuseOnTarget(executor)
        val opts = options
        val config = this
        if (opts.runner == ByTestRunner.DJANGO) return djangoState(executor, environment)
        if (opts.plainPytest) {
            // the interpreter it starts is this machine's, in the project here
            if (targetName != null) throw CantRunException(BasedPythonBundle.message("target.error.plainPytest"))
            val arguments = listOf("-m", ByPytest.MODULE, ByPytest.VERBOSE) + ParametersListUtil.parse(opts.paths)
            return PlainPythonTestCommandLineState(project, opts, environment, arguments) { handler, exec ->
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    FRAMEWORK_NAME, handler, testConsoleProperties(config, exec, fromTarget = null),
                )
            }
        }
        return object : ByCommandLineState(project, opts, environment) {
            // `by run pytest`, not `by test` — see [ByPytest].
            override val subcommand = "run"

            override fun buildSubcommandArgs(): List<String> = ByPytest.arguments(opts.paths)

            override val runnerEnvironment = mapOf(ByPytest.REPORTER_VARIABLE to ByPytest.REPORTER_TEAMCITY)

            // The editor labels them "Extra pytest args": they are pytest's, after its targets.
            override val extraArgsForProgram = true

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
                val fromTarget = fromTarget
                // The test console is the EDT's to build, and on a run target this runs on the thread
                // the target was prepared on (see ByTargetProgramRunner), which is where the process is
                // started and stays; on this machine it is the EDT already, and this runs inline.
                var result: ExecutionResult? = null
                ApplicationManager.getApplication().invokeAndWait {
                    val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                        FRAMEWORK_NAME, handler, testConsoleProperties(config, executor, fromTarget),
                    )
                    // built here rather than by the state, so the filter linking the target's paths,
                    // which the state adds to a console of its own, is added here
                    fromTarget?.let { console.addMessageFilter(ByConsoleFilter(project, it)) }
                    result = DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
                        .withTestRestartActions(console)
                }
                return checkNotNull(result)
            }
        }
    }

    /**
     * A run of django's `manage.py test` on [ByTestOptions.paths]' labels, beside `manage.py`: `by run
     * manage test -v 2` as a `by run` of `manage.py` is run, or, for tests already `.py` that `by run`
     * does not stage, the project's interpreter on `manage.py` itself.
     *
     * A `by run` is asked for its test reporter as a pytest run is: a `by` that speaks for django's
     * runner writes the run as service messages; until then the tree is read from the runner's `-v 2`
     * lines ([ByUnittestOutputParser]).
     */
    private fun djangoState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val config = this
        val manage = ByDjangoManage.find(project) ?: throw CantRunException(BasedPythonBundle.message("django.tests.noManage"))
        val plain = options.plainPytest
        // node ids a plain run names are relative to the project, `by run`'s to the tree it stages
        val base = if (plain) projectRelative(manage.directory) else ""
        val paths = ParametersListUtil.parse(options.paths)
        val labels = paths.map { path ->
            ByDjangoTests.label(path, base) ?: throw CantRunException(BasedPythonBundle.message("django.tests.noLabel", path, manage.directory))
        }
        val arguments = ByDjangoTests.ARGUMENTS + labels
        if (plain) {
            if (targetName != null) throw CantRunException(BasedPythonBundle.message("target.error.plainPytest"))
            val manageFile = manage.directory.resolve(manage.module + ".py").toString()
            return PlainPythonTestCommandLineState(project, options, environment, listOf(manageFile) + arguments, manage.directory.toString()) { handler, exec ->
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    DJANGO_FRAMEWORK_NAME, handler, djangoConsoleProperties(config, exec, null, base, labels),
                )
            }
        }
        val opts = ByTestOptions().also {
            it.copyFrom(options)
            it.workingDir = manage.directory.toString()
        }
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "run"

            override fun buildSubcommandArgs(): List<String> = listOf(manage.module) + arguments

            override val runnerEnvironment = mapOf(ByPytest.REPORTER_VARIABLE to ByPytest.REPORTER_TEAMCITY)

            override val extraArgsForProgram = true

            /** As the pytest run's: the SM runner attaches the console to the process this starts. */
            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                val handler = startProcess()
                val fromTarget = fromTarget
                var result: ExecutionResult? = null
                ApplicationManager.getApplication().invokeAndWait {
                    val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                        DJANGO_FRAMEWORK_NAME, handler, djangoConsoleProperties(config, executor, fromTarget, base, labels),
                    )
                    fromTarget?.let { console.addMessageFilter(ByConsoleFilter(project, it)) }
                    result = DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
                        .withTestRestartActions(console)
                }
                return checkNotNull(result)
            }
        }
    }

    /** [directory] relative to the project, `/`-terminated, or `""` for the project itself or a directory outside it. */
    private fun projectRelative(directory: Path): String {
        val root = project.basePath?.let(Path::of) ?: return ""
        if (!directory.startsWith(root)) return ""
        val relative = root.relativize(directory).joinToString("/")
        return if (relative.isEmpty()) "" else "$relative/"
    }

    /**
     * The console of a Django run: a tree built by node id, since a failure is only known once every
     * test has run ([ByUnittestOutputParser]); otherwise as a pytest run's. [base] is where `manage.py`
     * is, as the node ids of the run are written.
     */
    private fun djangoConsoleProperties(
        config: ByTestConfiguration,
        executor: Executor,
        fromTarget: ((String) -> Path?)?,
        base: String,
        labels: List<String>,
    ): SMTRunnerConsoleProperties =
        object : SMTRunnerConsoleProperties(config, DJANGO_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
            init {
                isIdBasedTestTree = true
            }

            override fun createTestEventsConverter(
                testFrameworkName: String,
                consoleProperties: TestConsoleProperties,
            ): OutputToGeneralTestEventsConverter =
                ByDjangoTestEventsConverter(testFrameworkName, consoleProperties, base, labels)

            override fun getTestLocator(): SMTestLocator = fromTarget?.let(::ByTargetTestLocator) ?: ByTestLocator

            override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
                ByRerunFailedTestsAction(consoleView, this)
        }

    /**
     * Wires the `by`-specific output parser and source locator into the SM test runner; [fromTarget]
     * translates the paths a run on a run target reports, null for a run on this machine.
     */
    private fun testConsoleProperties(
        config: ByTestConfiguration,
        executor: Executor,
        fromTarget: ((String) -> Path?)?,
    ): SMTRunnerConsoleProperties =
        object : SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
            override fun createTestEventsConverter(
                testFrameworkName: String,
                consoleProperties: TestConsoleProperties,
            ): OutputToGeneralTestEventsConverter =
                ByTestEventsConverter(testFrameworkName, consoleProperties)

            override fun getTestLocator(): SMTestLocator = fromTarget?.let(::ByTargetTestLocator) ?: ByTestLocator

            override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
                ByRerunFailedTestsAction(consoleView, this)
        }

    private companion object {
        const val FRAMEWORK_NAME = "pytest (by)"
        const val DJANGO_FRAMEWORK_NAME = "Django tests (by)"
    }
}
