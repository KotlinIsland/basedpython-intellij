package dev.basedpython.pycharm.run.test.tree

/**
 * A normalized, IDE-agnostic test event parsed from `by test` output.
 *
 * The parser ([ByTestOutputParser]) emits these so that the conversion to
 * TeamCity service messages ([ByServiceMessages]) and the wiring into the
 * SMTRunner test tree ([ByTestEventsConverter]) can be tested independently.
 */
sealed interface ByTestEvent {
    /** A test container (file / class / module) opened. */
    data class TestSuiteStarted(val name: String) : ByTestEvent

    /** The matching close for a previously started suite. */
    data class SuiteFinished(val name: String) : ByTestEvent

    /** A single test method/function started executing. */
    data class TestStarted(val name: String) : ByTestEvent

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
