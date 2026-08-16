package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.run.test.tree.ByTestLocator

/**
 * Feeds the node view the outcome of every test the SM runner reports.
 *
 * Subscribed rather than wired into the run configuration, so that a run started *anywhere* lands
 * in the view: the gutter icon, the run combo box, a rerun of failed tests, and the view's own Run
 * button all end up in the same test tree, and this listens to that tree.
 *
 * Registered from `basedpython-testrunner.xml`, which is loaded only when the test runner plugin is
 * present — the same reason the rest of the SM usage lives behind that optional dependency.
 *
 * Other frameworks' runs pass through here too, since the topic is per project rather than per
 * configuration. They are ignored for free: their tests carry no `by_test://` location, so nothing
 * matches a node id and nothing is recorded.
 */
internal class ByTestRunStateListener(private val project: Project) : SMTRunnerEventsListener {

    override fun onTestStarted(test: SMTestProxy) = record(test, ByTestState.RUNNING)

    override fun onTestFailed(test: SMTestProxy) = record(test, ByTestState.FAILED)

    override fun onTestIgnored(test: SMTestProxy) = record(test, ByTestState.SKIPPED)

    /**
     * A finished test that is neither failed nor ignored has passed.
     *
     * `onTestFinished` fires for every test, after the more specific event, so asking the proxy
     * rather than assuming is what keeps a failure from being overwritten by the finish that
     * follows it.
     */
    override fun onTestFinished(test: SMTestProxy) {
        val state = when {
            test.isIgnored -> ByTestState.SKIPPED
            test.isPassed -> ByTestState.PASSED
            else -> ByTestState.FAILED
        }
        record(test, state)
    }

    override fun onTestingFinished(root: SMTestProxy.SMRootTestProxy) {
        // Whatever was left mid-flight is not running any more, whether the run was stopped, the
        // interpreter died, or a test took the process down with it.
        ByTestNodeService.getInstance(project).clearRunning()
    }

    override fun onTestingStarted(root: SMTestProxy.SMRootTestProxy) = Unit
    override fun onTestsCountInSuite(count: Int) = Unit
    override fun onSuiteStarted(suite: SMTestProxy) = Unit
    override fun onSuiteFinished(suite: SMTestProxy) = Unit
    override fun onCustomProgressTestsCategory(categoryName: String?, testCount: Int) = Unit
    override fun onCustomProgressTestStarted() = Unit
    override fun onCustomProgressTestFailed() = Unit
    override fun onCustomProgressTestFinished() = Unit
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy?) = Unit
    override fun onSuiteTreeStarted(suite: SMTestProxy?) = Unit

    /**
     * Records [state] for [test], if it is one of ours.
     *
     * The link between a run's tree and the collected one is the location our converter emits:
     * `by_test://tests/test_math.py::TestGroup::test_add` is the node id the collected tree stores
     * as that node's target, verbatim.
     */
    private fun record(test: SMTestProxy, state: ByTestState) {
        val nodeId = nodeIdOf(test) ?: return
        ByTestNodeService.getInstance(project).setOutcome(nodeId, state)
    }

    private fun nodeIdOf(test: SMTestProxy): String? =
        test.locationUrl?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    private companion object {
        val PREFIX = "${ByTestLocator.PROTOCOL}://"
    }
}
