package dev.basedpython.pycharm.run.test.tree

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive tests for the pure [ByTestOutputParser]: pytest-verbose, unittest,
 * mixed input, summary-only, empty, garbage, and percentage-tag stripping.
 */
class ByTestOutputParserTest {

    private fun parser() = ByTestOutputParser()

    // ---- pytest verbose ----------------------------------------------------

    @Test
    fun `pytest passed emits suite started, test started, passed, finished`() {
        val events = parser().parseLine("tests/test_math.py::test_add PASSED")
        assertEquals(
            listOf(
                ByTestEvent.TestSuiteStarted("tests/test_math.py"),
                ByTestEvent.TestStarted("test_add"),
                ByTestEvent.TestPassed("test_add"),
                ByTestEvent.TestFinished("test_add"),
            ),
            events,
        )
    }

    @Test
    fun `pytest failed produces a TestFailed event`() {
        val events = parser().parseLine("tests/test_math.py::test_sub FAILED")
        assertTrue(events.any { it is ByTestEvent.TestFailed && it.name == "test_sub" })
    }

    @Test
    fun `pytest error maps to TestFailed`() {
        val events = parser().parseLine("a/b.py::test_x ERROR")
        assertTrue(events.any { it is ByTestEvent.TestFailed })
    }

    @Test
    fun `pytest skipped maps to TestIgnored`() {
        val events = parser().parseLine("a/b.py::test_x SKIPPED")
        assertTrue(events.any { it is ByTestEvent.TestIgnored && it.name == "test_x" })
    }

    @Test
    fun `pytest xfail maps to TestIgnored`() {
        val events = parser().parseLine("a/b.py::test_x XFAIL")
        assertTrue(events.any { it is ByTestEvent.TestIgnored })
    }

    @Test
    fun `pytest xpass maps to TestPassed`() {
        val events = parser().parseLine("a/b.py::test_x XPASS")
        assertTrue(events.any { it is ByTestEvent.TestPassed })
    }

    @Test
    fun `pytest percentage tag is stripped from outcome`() {
        val events = parser().parseLine("tests/test_math.py::test_add PASSED [ 50%]")
        assertTrue(events.any { it is ByTestEvent.TestPassed && it.name == "test_add" })
    }

    @Test
    fun `pytest percentage tag at 100 percent`() {
        val events = parser().parseLine("tests/test_math.py::test_add PASSED [100%]")
        assertTrue(events.any { it is ByTestEvent.TestPassed })
    }

    @Test
    fun `pytest lowercase outcome is recognized`() {
        val events = parser().parseLine("tests/t.py::test_a passed")
        assertTrue(events.any { it is ByTestEvent.TestPassed })
    }

    @Test
    fun `pytest test name extracted after double colon`() {
        val events = parser().parseLine("pkg/sub/test_mod.py::TestClass::test_method PASSED")
        assertTrue(events.any { it is ByTestEvent.TestStarted && it.name == "test_method" })
    }

    @Test
    fun `pytest suite name is the file path before colon`() {
        val events = parser().parseLine("pkg/sub/test_mod.py::test_x PASSED")
        assertTrue(events.any { it is ByTestEvent.TestSuiteStarted && it.name == "pkg/sub/test_mod.py" })
    }

    // ---- suite transitions -------------------------------------------------

    @Test
    fun `same suite is opened only once across two tests`() {
        val p = parser()
        val all = p.parseLine("a.py::t1 PASSED") + p.parseLine("a.py::t2 PASSED")
        assertEquals(1, all.count { it is ByTestEvent.TestSuiteStarted })
    }

    @Test
    fun `switching suite closes the previous one`() {
        val p = parser()
        val all = p.parseLine("a.py::t1 PASSED") + p.parseLine("b.py::t2 PASSED")
        assertTrue(all.any { it is ByTestEvent.SuiteFinished && it.name == "a.py" })
        assertEquals(2, all.count { it is ByTestEvent.TestSuiteStarted })
    }

    @Test
    fun `finish closes a still-open suite`() {
        val p = parser()
        p.parseLine("a.py::t1 PASSED")
        assertEquals(listOf(ByTestEvent.SuiteFinished("a.py")), p.finish())
    }

    @Test
    fun `finish on empty parser yields nothing`() {
        assertTrue(parser().finish().isEmpty())
    }

    @Test
    fun `finish is idempotent after closing`() {
        val p = parser()
        p.parseLine("a.py::t1 PASSED")
        p.finish()
        assertTrue(p.finish().isEmpty())
    }

    // ---- unittest verbose --------------------------------------------------

    @Test
    fun `unittest ok maps to passed`() {
        val events = parser().parseLine("test_add (mymod.MathTest) ... ok")
        assertTrue(events.any { it is ByTestEvent.TestPassed && it.name == "test_add" })
        assertTrue(events.any { it is ByTestEvent.TestSuiteStarted && it.name == "mymod.MathTest" })
    }

    @Test
    fun `unittest FAIL maps to failed`() {
        val events = parser().parseLine("test_sub (mymod.MathTest) ... FAIL")
        assertTrue(events.any { it is ByTestEvent.TestFailed && it.name == "test_sub" })
    }

    @Test
    fun `unittest ERROR maps to failed`() {
        val events = parser().parseLine("test_boom (m.C) ... ERROR")
        assertTrue(events.any { it is ByTestEvent.TestFailed })
    }

    @Test
    fun `unittest skipped maps to ignored with reason`() {
        val events = parser().parseLine("test_skip (m.C) ... skipped 'not on windows'")
        val ig = events.filterIsInstance<ByTestEvent.TestIgnored>().single()
        assertEquals("test_skip", ig.name)
        assertEquals("not on windows", ig.message)
    }

    @Test
    fun `unittest expected failure maps to ignored`() {
        val events = parser().parseLine("test_x (m.C) ... expected failure")
        assertTrue(events.any { it is ByTestEvent.TestIgnored })
    }

    @Test
    fun `unittest unexpected success maps to failed`() {
        val events = parser().parseLine("test_x (m.C) ... unexpected success")
        assertTrue(events.any { it is ByTestEvent.TestFailed })
    }

    @Test
    fun `unittest without ellipsis still parses`() {
        val events = parser().parseLine("test_add (m.C) ok")
        assertTrue(events.any { it is ByTestEvent.TestPassed })
    }

    // ---- summary dots ------------------------------------------------------

    @Test
    fun `summary dots counts passes failures and skips`() {
        val r = parser().parseSummaryDots("....F..s")
        assertEquals(Triple(6, 1, 1), r)
    }

    @Test
    fun `summary dots strips trailing percentage tag`() {
        val r = parser().parseSummaryDots("....F [ 80%]")
        assertEquals(Triple(4, 1, 0), r)
    }

    @Test
    fun `summary dots all passing`() {
        assertEquals(Triple(5, 0, 0), parser().parseSummaryDots("....."))
    }

    @Test
    fun `summary dots rejects non progress line`() {
        assertNull(parser().parseSummaryDots("hello world"))
    }

    @Test
    fun `summary dots rejects empty`() {
        assertNull(parser().parseSummaryDots(""))
        assertNull(parser().parseSummaryDots("   "))
    }

    @Test
    fun `error char E counts as failure`() {
        assertEquals(Triple(0, 1, 0), parser().parseSummaryDots("E"))
    }

    // ---- summary footer line ----------------------------------------------

    @Test
    fun `summary footer line produces no tree events`() {
        assertTrue(parser().parseLine("=== 3 passed, 1 failed in 0.12s ===").isEmpty())
    }

    @Test
    fun `summary footer with only passes produces no events`() {
        assertTrue(parser().parseLine("=== 5 passed in 0.01s ===").isEmpty())
    }

    // ---- empty / garbage ---------------------------------------------------

    @Test
    fun `empty line yields no events`() {
        assertTrue(parser().parseLine("").isEmpty())
        assertTrue(parser().parseLine("   ").isEmpty())
    }

    @Test
    fun `garbage line yields no events`() {
        assertTrue(parser().parseLine("this is just some banner text").isEmpty())
    }

    @Test
    fun `decorative separator yields no events`() {
        assertTrue(parser().parseLine("============================= test session starts").isEmpty())
    }

    @Test
    fun `line without outcome keyword is ignored`() {
        assertTrue(parser().parseLine("tests/test_math.py::test_add").isEmpty())
    }

    @Test
    fun `trailing CR and LF are trimmed`() {
        val events = parser().parseLine("a.py::t1 PASSED\r\n")
        assertTrue(events.any { it is ByTestEvent.TestPassed })
    }

    // ---- mixed / whole blob ------------------------------------------------

    @Test
    fun `parseAll over mixed pytest blob balances suites`() {
        val blob = """
            ============ test session starts ============
            tests/test_a.py::test_one PASSED [ 33%]
            tests/test_a.py::test_two FAILED [ 66%]
            tests/test_b.py::test_three SKIPPED [100%]
            === 1 passed, 1 failed, 1 skipped in 0.05s ===
        """.trimIndent()
        val events = parser().parseAll(blob)
        assertEquals(2, events.count { it is ByTestEvent.TestSuiteStarted })
        assertEquals(2, events.count { it is ByTestEvent.SuiteFinished })
        assertTrue(events.any { it is ByTestEvent.TestFailed && it.name == "test_two" })
        assertTrue(events.any { it is ByTestEvent.TestIgnored && it.name == "test_three" })
    }

    @Test
    fun `parseAll counts are tallied`() {
        val p = parser()
        p.parseAll("a.py::t1 PASSED\na.py::t2 FAILED\na.py::t3 SKIPPED")
        assertEquals(1, p.passed)
        assertEquals(1, p.failed)
        assertEquals(1, p.ignored)
    }

    @Test
    fun `parseAll on empty string yields no events`() {
        assertTrue(parser().parseAll("").isEmpty())
    }

    @Test
    fun `mixed pytest and unittest in one blob`() {
        val blob = "a.py::t1 PASSED\ntest_b (m.C) ... ok"
        val events = parser().parseAll(blob)
        assertEquals(2, events.count { it is ByTestEvent.TestPassed })
    }
}
