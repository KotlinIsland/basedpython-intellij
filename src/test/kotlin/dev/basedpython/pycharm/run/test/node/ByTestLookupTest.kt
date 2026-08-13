package dev.basedpython.pycharm.run.test.node

import dev.basedpython.pycharm.run.test.ByDeclarationPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * When a declaration counts as a test.
 *
 * The interesting question is not the tests pytest collected — those are simply in the index — but
 * what a *silence* means. A file the collection swept without naming is evidence that it holds no
 * tests; a file it could not have seen is not.
 */
class ByTestLookupTest {

    private val collected = ByTestIndex.of(
        ByCollection(
            nodeIds = listOf(
                "tests/test_math.py::test_add",
                "tests/test_math.py::test_param[1-2]",
                "tests/test_math.py::test_param[3-4]",
                "tests/test_math.py::TestGroup::test_in_class",
            ),
        ),
        takenAtMillis = COLLECTED_AT,
    )

    private fun verdict(
        index: ByTestIndex = collected,
        path: String? = "tests/test_math.by",
        symbols: List<String> = listOf("test_add"),
        isClass: Boolean = false,
        changed: Boolean = false,
    ) = ByTestLookup.verdict(index, path, ByDeclarationPath(symbols, isClass), changed)

    @Test
    fun `a collected test carries the count of what running it would run`() {
        assertEquals(ByTestLookup.Verdict.Tests(1), verdict(symbols = listOf("test_add")))
        assertEquals(ByTestLookup.Verdict.Tests(2), verdict(symbols = listOf("test_param")))
        assertEquals(
            ByTestLookup.Verdict.Tests(1),
            verdict(symbols = listOf("TestGroup", "test_in_class")),
        )
        assertEquals(ByTestLookup.Verdict.Tests(1), verdict(symbols = listOf("TestGroup"), isClass = true))
    }

    @Test
    fun `a declaration passed over in a collected file is not a test, however it is named`() {
        // `def test_helper` inside a `class Helper`: pytest collects nothing in a non-Test class,
        // and the name is not a second opinion.
        assertEquals(
            ByTestLookup.Verdict.NotATest,
            verdict(symbols = listOf("Helper", "test_helper")),
        )
        assertEquals(ByTestLookup.Verdict.NotATest, verdict(symbols = listOf("test_not_collected")))
    }

    @Test
    fun `a file the sweep never named holds no tests, even one named like a test`() {
        // The `def test_x` in `main.by` case: pytest collects `test_*.py` files, walked the whole
        // project, and never mentioned this one. Running it would collect nothing.
        assertEquals(
            ByTestLookup.Verdict.NotATest,
            verdict(path = "main.by", symbols = listOf("test_x")),
        )
    }

    @Test
    fun `a file changed since the sweep is unknown again, so a new test keeps its icon`() {
        assertEquals(
            ByTestLookup.Verdict.Unknown,
            verdict(path = "main.by", symbols = listOf("test_x"), changed = true),
        )
        assertEquals(
            ByTestLookup.Verdict.Unknown,
            verdict(path = "tests/test_new.by", symbols = listOf("test_fresh"), changed = true),
        )
    }

    @Test
    fun `an interrupted collection proves nothing about the files it never reached`() {
        val interrupted = ByTestIndex.of(
            ByCollection(
                nodeIds = listOf("tests/test_math.py::test_add"),
                errors = listOf(ByCollectionError("tests/test_broken.py", "RuntimeError: boom")),
            ),
            takenAtMillis = COLLECTED_AT,
        )
        assertEquals(
            ByTestLookup.Verdict.Unknown,
            verdict(index = interrupted, path = "main.by", symbols = listOf("test_x")),
        )
        // What it did collect is still known.
        assertEquals(
            ByTestLookup.Verdict.Tests(1),
            verdict(index = interrupted, symbols = listOf("test_add")),
        )
    }

    @Test
    fun `with nothing collected the name is the only evidence there is`() {
        assertEquals(
            ByTestLookup.Verdict.Unknown,
            verdict(index = ByTestIndex.EMPTY, symbols = listOf("test_add")),
        )
        assertEquals(
            ByTestLookup.Verdict.NotATest,
            verdict(index = ByTestIndex.EMPTY, symbols = listOf("helper")),
        )
        assertEquals(
            ByTestLookup.Verdict.Unknown,
            verdict(index = ByTestIndex.EMPTY, symbols = listOf("TestGroup"), isClass = true),
        )
    }

    @Test
    fun `a file outside the project falls back to its name`() {
        assertEquals(ByTestLookup.Verdict.Unknown, verdict(path = null, symbols = listOf("test_add")))
        assertEquals(ByTestLookup.Verdict.NotATest, verdict(path = null, symbols = listOf("helper")))
    }

    private companion object {
        /** Any fixed instant; the rule only compares against it. */
        const val COLLECTED_AT = 1_700_000_000_000L
    }
}
