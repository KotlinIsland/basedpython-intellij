package dev.basedpython.pycharm.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A test configuration's targets name the project's own files, which is what a run in place hands
 * pytest; a run that is not in place names each `.by` where `by` says its tree holds the python.
 */
class ByTestTargetsTest {

    @Test
    fun `the by files a configuration names are the ones a staged run asks about`() {
        assertEquals(
            listOf("src/tests/test_a.by", "tests/test_b.by"),
            ByTestTargets.sources("src/tests/test_a.by::test_x src/tests/test_a.by::TestG::test_y tests/test_b.by tests/test_plain.py tests"),
        )
    }

    /** `src/tests/test_a.by` is staged as `tests/test_a.py`: a src layout's tree follows the module tree. */
    @Test
    fun `a staged run names each by where the tree holds it, with its names after it`() {
        val staged = ByTestTargets.staged("src/tests/test_a.by::TestG::test_y[1-2] tests/test_plain.py tests") {
            mapOf("src/tests/test_a.by" to "tests/test_a.py")[it]
        }
        assertEquals(ByTestTargets.Staged.Paths("tests/test_a.py::TestG::test_y[1-2] tests/test_plain.py tests"), staged)
    }

    @Test
    fun `a by whose staging by has not said is no target at all`() {
        assertEquals(ByTestTargets.Staged.Unknown("tests/test_b.by"), ByTestTargets.staged("tests/test_b.by::test_x") { null })
    }

    @Test
    fun `a target with a space stays one`() {
        assertEquals(
            ByTestTargets.Staged.Paths(""""my tests/test_x.py::test_y"""") ,
            ByTestTargets.staged(""""my tests/test_x.by::test_y"""") { "my tests/test_x.py" },
        )
    }
}
