package dev.basedpython.pycharm.run.test

import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.ByTargetAwareConfiguration
import dev.basedpython.pycharm.run.ByInPlace
import dev.basedpython.pycharm.lsp.byBlocking
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.run.model.stagedPath
import dev.basedpython.pycharm.run.ergonomics.ByConsoleFilter
import dev.basedpython.pycharm.run.test.tree.ByTargetTestLocator
import dev.basedpython.pycharm.run.test.tree.ByTestSources
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
     * For tests that are already `.py`, with a `by` that stages the project: see [ByTestTargets.plain]
     * for why. The node view sets this when what it is running came from the plain half.
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
                    FRAMEWORK_NAME, handler, testConsoleProperties(config, exec, fromTarget = null, projectPaths = true),
                )
            }
        }
        val decision = ByInPlace.of(project, byFlags = "", onTarget = targetName != null)
        // asked of the project's `by` now for a run here, and once the target's `by` has said for a
        // run there
        val stagedHere = if (!decision.waitsForTarget && !decision.value) stagedPaths(opts) else null
        return object : ByCommandLineState(project, opts, environment) {
            // `by run pytest`, not `by test` — see [ByPytest].
            override val subcommand = "run"

            override val inPlaceDecision: ByInPlace = decision

            override fun buildSubcommandArgs(): List<String> =
                ByPytest.arguments(if (inPlace) opts.paths else stagedHere ?: stagedPaths(opts), inPlace)

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
                        FRAMEWORK_NAME, handler, testConsoleProperties(config, executor, fromTarget, projectPaths = inPlace),
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
     * manage.py test -v 2` as a `by run` of `manage.py` is run, or, for tests already `.py` with a `by` that
     * stages the project ([ByTestTargets.plain]), the project's interpreter on `manage.py` itself.
     *
     * A `by run` is asked for its test reporter as a pytest run is: a `by` that speaks for django's
     * runner writes the run as service messages; until then the tree is read from the runner's `-v 2`
     * lines ([ByUnittestOutputParser]).
     */
    private fun djangoState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val config = this
        val manage = ByDjangoManage.find(project) ?: throw CantRunException(BasedPythonBundle.message("django.tests.noManage"))
        if (options.plainPytest) {
            if (targetName != null) throw CantRunException(BasedPythonBundle.message("target.error.plainPytest"))
            val run = djangoRun(manage, projectPaths = true)
            val manageFile = manage.directory.resolve(manage.script).toString()
            return PlainPythonTestCommandLineState(project, options, environment, listOf(manageFile) + run.arguments, manage.directory.toString()) { handler, exec ->
                SMTestRunnerConnectionUtil.createAndAttachConsole(
                    DJANGO_FRAMEWORK_NAME, handler, djangoConsoleProperties(config, exec, null, run, projectPaths = true),
                )
            }
        }
        val decision = ByInPlace.of(project, byFlags = "", onTarget = targetName != null)
        // worked out now for a run here, so a test that cannot be labelled is said before anything
        // starts, and once the target's `by` has said for a run there, as a pytest run's targets are
        val runHere = if (decision.waitsForTarget) null else djangoRun(manage, projectPaths = decision.value)
        val opts = ByTestOptions().also {
            it.copyFrom(options)
            it.workingDir = manage.directory.toString()
        }
        return object : ByCommandLineState(project, opts, environment) {
            override val subcommand = "run"

            override val inPlaceDecision: ByInPlace = decision

            private val run: DjangoRun by lazy { runHere ?: djangoRun(manage, projectPaths = inPlace) }

            override fun buildSubcommandArgs(): List<String> = listOf(manage.script) + run.arguments

            override val runnerEnvironment = mapOf(ByPytest.REPORTER_VARIABLE to ByPytest.REPORTER_TEAMCITY)

            override val extraArgsForProgram = true

            /** As the pytest run's: the SM runner attaches the console to the process this starts. */
            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                val handler = startProcess()
                val fromTarget = fromTarget
                var result: ExecutionResult? = null
                ApplicationManager.getApplication().invokeAndWait {
                    val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
                        DJANGO_FRAMEWORK_NAME, handler, djangoConsoleProperties(config, executor, fromTarget, run, projectPaths = inPlace),
                    )
                    fromTarget?.let { console.addMessageFilter(ByConsoleFilter(project, it)) }
                    result = DefaultExecutionResult(console, handler, *createActions(console, handler, executor))
                        .withTestRestartActions(console)
                }
                return checkNotNull(result)
            }
        }
    }

    /**
     * What a Django run names to `manage.py test`, and how the modules its runner prints are named
     * back as node ids: [labels] and the [arguments] they are part of, and [moduleFile].
     */
    private class DjangoRun(val labels: List<String>, val moduleFile: (String) -> String) {
        val arguments: List<String> get() = ByDjangoTests.ARGUMENTS + labels
    }

    /**
     * The Django run of [ByTestOptions.paths] beside [manage], naming the project's files when
     * [projectPaths] — a plain run, or a `by run` in place — and otherwise the files of the tree a
     * `by run` stages, where each target is written first ([stagedPaths]) and the tree's root merges
     * the module roots, so the paths are from its root.
     *
     * @throws CantRunException for a target that is no module `manage.py test` can be named.
     */
    private fun djangoRun(manage: ByDjangoManage, projectPaths: Boolean): DjangoRun {
        val base = if (projectPaths) projectRelative(manage.directory) else ""
        val paths = ParametersListUtil.parse(if (projectPaths) options.paths else stagedPaths(options))
        val labels = paths.map { path ->
            ByDjangoTests.label(path, base) ?: throw CantRunException(BasedPythonBundle.message("django.tests.noLabel", path, manage.directory))
        }
        // a module unittest names is the file the project holds for it, a `.by` or a `.py`; in a
        // staged tree, the python every module is there
        return DjangoRun(labels) { module ->
            val by = ByDjangoTests.moduleFile(module, base, ByDjangoTests.BY)
            if (projectPaths && ByTestSources.findSourceFile(project, by) != null) by else ByDjangoTests.moduleFile(module, base)
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
     * test has run ([ByUnittestOutputParser]); otherwise as a pytest run's. [run] names the modules
     * its runner prints by their files, and [projectPaths] says those are the project's.
     */
    private fun djangoConsoleProperties(
        config: ByTestConfiguration,
        executor: Executor,
        fromTarget: ((String) -> Path?)?,
        run: DjangoRun,
        projectPaths: Boolean,
    ): SMTRunnerConsoleProperties =
        object : SMTRunnerConsoleProperties(config, DJANGO_FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
            init {
                isIdBasedTestTree = true
            }

            override fun createTestEventsConverter(
                testFrameworkName: String,
                consoleProperties: TestConsoleProperties,
            ): OutputToGeneralTestEventsConverter =
                ByDjangoTestEventsConverter(testFrameworkName, consoleProperties, run.moduleFile, run.labels)

            override fun getTestLocator(): SMTestLocator {
                val locator = ByTestLocator(projectPaths)
                return fromTarget?.let { ByTargetTestLocator(locator, it) } ?: locator
            }

            override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
                ByRerunFailedTestsAction(consoleView, this)
        }

    /**
     * [ByTestOptions.paths] as pytest is handed them in the tree `by run` stages, for a run that is
     * not in place: each `.by` target named where `by` says the tree holds it ([ByTestTargets.staged]),
     * asked of the project's `by` from whichever thread the run is started on.
     *
     * @throws CantRunException when `by` does not say where a `.by` target is staged — no `by` is
     *   running, or it does not place the file — since a target pytest cannot find runs nothing.
     */
    private fun stagedPaths(opts: ByTestOptions): String {
        val sources = ByTestTargets.sources(opts.paths)
        if (sources.isEmpty()) return opts.paths
        val staged = byBlocking(project, BasedPythonBundle.message("run.askingFlags")) {
            val outputs = ByBuildOutputs.getInstance(project)
            val where = sources.associateWith { source ->
                ByTestSources.findSourceFile(project, source)?.let { outputs.of(it)?.stagedPath }
            }
            ByTestTargets.staged(opts.paths) { where[it] }
        }
        return when (staged) {
            is ByTestTargets.Staged.Paths -> staged.paths
            is ByTestTargets.Staged.Unknown -> throw CantRunException(BasedPythonBundle.message("test.error.stagedUnknown", staged.source))
        }
    }

    /**
     * Wires the `by`-specific output parser and source locator into the SM test runner; [fromTarget]
     * translates the paths a run on a run target reports, null for a run on this machine, and
     * [projectPaths] says the node ids the run reports name the project's own files, as those of
     * plain pytest and of `by run --in-place` do.
     */
    private fun testConsoleProperties(
        config: ByTestConfiguration,
        executor: Executor,
        fromTarget: ((String) -> Path?)?,
        projectPaths: Boolean,
    ): SMTRunnerConsoleProperties =
        object : SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
            override fun createTestEventsConverter(
                testFrameworkName: String,
                consoleProperties: TestConsoleProperties,
            ): OutputToGeneralTestEventsConverter =
                ByTestEventsConverter(testFrameworkName, consoleProperties)

            override fun getTestLocator(): SMTestLocator {
                val locator = ByTestLocator(projectPaths)
                return fromTarget?.let { ByTargetTestLocator(locator, it) } ?: locator
            }

            override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
                ByRerunFailedTestsAction(consoleView, this)
        }

    private companion object {
        const val FRAMEWORK_NAME = "pytest (by)"
        const val DJANGO_FRAMEWORK_NAME = "Django tests (by)"
    }
}
