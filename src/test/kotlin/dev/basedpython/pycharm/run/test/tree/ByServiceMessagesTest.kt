package dev.basedpython.pycharm.run.test.tree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that [ByServiceMessages] produces correct TeamCity `##teamcity[...]`
 * strings with proper attribute escaping for every [ByTestEvent] variant.
 */
class ByServiceMessagesTest {

    private fun msg(e: ByTestEvent) = ByServiceMessages.toServiceMessage(e)

    // ---- escaping ----------------------------------------------------------

    @Test
    fun `escape pipe doubles it`() {
        assertEquals("a||b", ByServiceMessages.escape("a|b"))
    }

    @Test
    fun `escape single quote`() {
        assertEquals("it|'s", ByServiceMessages.escape("it's"))
    }

    @Test
    fun `escape newline`() {
        assertEquals("a|nb", ByServiceMessages.escape("a\nb"))
    }

    @Test
    fun `escape carriage return`() {
        assertEquals("a|rb", ByServiceMessages.escape("a\rb"))
    }

    @Test
    fun `escape brackets`() {
        assertEquals("|[x|]", ByServiceMessages.escape("[x]"))
    }

    @Test
    fun `escape leaves plain text untouched`() {
        assertEquals("hello world 123", ByServiceMessages.escape("hello world 123"))
    }

    @Test
    fun `escape handles combination`() {
        assertEquals("a|nb|'c||d", ByServiceMessages.escape("a\nb'c|d"))
    }

    @Test
    fun `escape empty string`() {
        assertEquals("", ByServiceMessages.escape(""))
    }

    // ---- per-event messages ------------------------------------------------

    @Test
    fun `suite started message`() {
        assertEquals(
            "##teamcity[testSuiteStarted name='a.py']",
            msg(ByTestEvent.TestSuiteStarted("a.py")),
        )
    }

    @Test
    fun `suite finished message`() {
        assertEquals(
            "##teamcity[testSuiteFinished name='a.py']",
            msg(ByTestEvent.SuiteFinished("a.py")),
        )
    }

    @Test
    fun `test started message includes captureStandardOutput`() {
        assertEquals(
            "##teamcity[testStarted name='test_add' captureStandardOutput='true']",
            msg(ByTestEvent.TestStarted("test_add")),
        )
    }

    @Test
    fun `test passed maps to testFinished`() {
        assertEquals(
            "##teamcity[testFinished name='test_add']",
            msg(ByTestEvent.TestPassed("test_add")),
        )
    }

    @Test
    fun `test finished message`() {
        assertEquals(
            "##teamcity[testFinished name='test_add']",
            msg(ByTestEvent.TestFinished("test_add")),
        )
    }

    @Test
    fun `test failed with message only`() {
        assertEquals(
            "##teamcity[testFailed name='test_x' message='boom']",
            msg(ByTestEvent.TestFailed("test_x", "boom")),
        )
    }

    @Test
    fun `test failed with details`() {
        assertEquals(
            "##teamcity[testFailed name='test_x' message='boom' details='Traceback...']",
            msg(ByTestEvent.TestFailed("test_x", "boom", "Traceback...")),
        )
    }

    @Test
    fun `test failed omits empty details`() {
        assertFalse(msg(ByTestEvent.TestFailed("t", "m", "")).contains("details="))
    }

    @Test
    fun `test ignored with message`() {
        assertEquals(
            "##teamcity[testIgnored name='test_x' message='skip reason']",
            msg(ByTestEvent.TestIgnored("test_x", "skip reason")),
        )
    }

    @Test
    fun `test ignored omits empty message`() {
        assertEquals(
            "##teamcity[testIgnored name='test_x']",
            msg(ByTestEvent.TestIgnored("test_x", "")),
        )
    }

    // ---- escaping inside messages -----------------------------------------

    @Test
    fun `name with special chars is escaped in message`() {
        val out = msg(ByTestEvent.TestSuiteStarted("a[b].py"))
        assertEquals("##teamcity[testSuiteStarted name='a|[b|].py']", out)
    }

    @Test
    fun `failure message newline is escaped`() {
        val out = msg(ByTestEvent.TestFailed("t", "line1\nline2"))
        assertTrue(out.contains("message='line1|nline2'"))
    }

    @Test
    fun `failure details with quotes escaped`() {
        val out = msg(ByTestEvent.TestFailed("t", "m", "got 'x'"))
        assertTrue(out.contains("details='got |'x|''"))
    }

    @Test
    fun `every message starts with teamcity prefix and ends with bracket`() {
        val events = listOf(
            ByTestEvent.TestSuiteStarted("s"),
            ByTestEvent.SuiteFinished("s"),
            ByTestEvent.TestStarted("t"),
            ByTestEvent.TestPassed("t"),
            ByTestEvent.TestFailed("t", "m", "d"),
            ByTestEvent.TestIgnored("t", "i"),
            ByTestEvent.TestFinished("t"),
        )
        for (e in events) {
            val m = msg(e)
            assertTrue(m, m.startsWith("##teamcity["))
            assertTrue(m, m.endsWith("]"))
        }
    }
}
