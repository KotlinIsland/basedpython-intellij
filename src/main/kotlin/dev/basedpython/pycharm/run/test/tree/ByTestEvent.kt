package dev.basedpython.pycharm.run.test.tree

/**
 * A normalized, IDE-agnostic test event parsed from `by run pytest` output.
 *
 * The parser ([ByTestOutputParser]) emits these so that the conversion to
 * TeamCity service messages ([ByServiceMessages]) and the wiring into the
 * SMTRunner test tree ([ByTestEventsConverter]) can be tested independently.
 */
sealed interface ByTestEvent {
    /**
     * A test container (file / class / module) opened.
     *
     * [locationHint] is the `by_test://…` URL [ByTestLocator] resolves back to a `.by` file, or
     * null when the output gave nothing to navigate to. Without it a tree node is inert: SMTRunner
     * only offers "jump to source" for nodes that carry one.
     */
    data class TestSuiteStarted(val name: String, val locationHint: String? = null) : ByTestEvent

    /** The matching close for a previously started suite. */
    data class SuiteFinished(val name: String) : ByTestEvent

    /** A single test method/function started executing. See [TestSuiteStarted.locationHint]. */
    data class TestStarted(val name: String, val locationHint: String? = null) : ByTestEvent

    /** A test completed successfully. */
    data class TestPassed(val name: String) : ByTestEvent

    /**
     * A test failed (assertion) or errored.
     * [message] is the short one-line reason; [details] is an optional longer
     * traceback/stdout block (may be empty).
     */
    data class TestFailed(val name: String, val message: String, val details: String = "") : ByTestEvent

    /** A test was skipped / ignored. */
    data class TestIgnored(val name: String, val message: String = "") : ByTestEvent

    /** A test finished (regardless of outcome). Emitted after pass/fail/ignore. */
    data class TestFinished(val name: String) : ByTestEvent
}
