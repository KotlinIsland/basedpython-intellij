package dev.basedpython.pycharm.run.test

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.run.test.tree.ByTestLocator

/**
 * *Rerun Failed Tests* for a `.by` test run: the same configuration, run on the failed tests' node
 * ids alone.
 *
 * Each test in the results tree carries the `by_test://` URL of its pytest node id, as pytest
 * reported it for the staged tree (`tests/test_geometry.py::TestClassify::test_square`), and a node
 * id is what [ByTestOptions.paths] takes — so the rerun is the run's own configuration with its
 * paths swapped for those ids, and nothing is re-derived from the tree.
 */
internal class ByRerunFailedTestsAction(console: ComponentContainer, properties: TestConsoleProperties) :
    AbstractRerunFailedTestsAction(console) {

    init {
        init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val configuration = myConsoleProperties.configuration as? ByTestConfiguration ?: return null
        // the tests alone: a failed file or class is failed for one of them, and naming it reruns all of it
        val failed = ByRerunTargets.nodeIds(getFailedTests(configuration.project).filter { it.isLeaf }.map { it.locationUrl })
        if (failed.isEmpty()) return null
        val rerun = configuration.clone() as ByTestConfiguration
        rerun.options.paths = ParametersListUtil.join(failed)
        return object : MyRunProfile(rerun) {
            override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState = rerun.getState(executor, env)
        }
    }
}

/** The pytest node ids failed tests are rerun on. */
object ByRerunTargets {

    /** The node id each `by_test://` URL of [locations] names, once each, in order; anything else is left out. */
    fun nodeIds(locations: List<String?>): List<String> {
        val prefix = ByTestLocator.PROTOCOL + "://"
        return locations.mapNotNull { url -> url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotBlank() } }
            .distinct()
    }
}

/**
 * The run window's restart actions for a `.by` test run, as Python's and JUnit's test runs have:
 * *Rerun Failed Tests* and *Rerun Automatically*.
 */
internal fun DefaultExecutionResult.withTestRestartActions(console: ConsoleView): DefaultExecutionResult {
    val view = console as? SMTRunnerConsoleView ?: return this
    val rerun = (view.properties as? SMTRunnerConsoleProperties)?.createRerunFailedTestsAction(console)
    rerun?.setModel(view.resultsViewer)
    setRestartActions(*listOfNotNull(rerun, ToggleAutoTestAction()).toTypedArray())
    return this
}
