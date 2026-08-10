package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Inverting `_by_sourcemap.py`.
 *
 * The forward table is dense over *generated* lines and points back at `.by` lines; what
 * `setPydevdSourceMap` wants is the other direction, expressed as runs. Everything that makes that
 * non-trivial is a real property of the transpiler: a prelude whose size depends on which features
 * the file uses, and single `.by` lines that become several generated ones.
 */
class ByLineMappingTest {

    @Test
    fun `a constant prelude offset collapses to a single run`() {
        // Three prelude lines, then demo.by lines 1-4 emitted one for one.
        val lines = listOf(null, null, null, 0, 1, 2, 3)
        assertEquals(listOf(ByLineRun(line = 1, endLine = 4, runtimeLine = 4)), ByLineMapping.invertLines(lines))
    }

    @Test
    fun `an expanded statement splits the run in two`() {
        // `data class Point:` on .by line 3 emits @dataclass(slots=True) and class Point:.
        val lines = listOf(0, 1, 2, 2, 3, 4)
        assertEquals(
            listOf(
                ByLineRun(line = 1, endLine = 3, runtimeLine = 1),
                ByLineRun(line = 4, endLine = 5, runtimeLine = 5),
            ),
            ByLineMapping.invertLines(lines),
        )
    }

    /** A breakpoint belongs on the line the statement starts at, not on its continuation. */
    @Test
    fun `a by line claimed by several generated lines keeps the first`() {
        val runs = ByLineMapping.invertLines(listOf(null, 7, 7, 7))
        assertEquals(listOf(ByLineRun(line = 8, endLine = 8, runtimeLine = 2)), runs)
    }

    @Test
    fun `a gap in the by lines starts a new run`() {
        // .by line 2 (index 1) produced nothing — a comment that was dropped, say.
        val lines = listOf(0, 2, 3)
        assertEquals(
            listOf(
                ByLineRun(line = 1, endLine = 1, runtimeLine = 1),
                ByLineRun(line = 3, endLine = 4, runtimeLine = 2),
            ),
            ByLineMapping.invertLines(lines),
        )
    }

    @Test
    fun `prelude-only output maps nothing`() {
        assertTrue(ByLineMapping.invertLines(listOf(null, null)).isEmpty())
        assertTrue(ByLineMapping.invertLines(emptyList()).isEmpty())
    }

    /** Runs must arrive sorted by source line — pydevd bisects them by exactly that key. */
    @Test
    fun `runs are ordered by source line even when the output is not`() {
        // A hoisted import: .by line 5 emitted before .by line 1.
        val runs = ByLineMapping.invertLines(listOf(4, 0, 1))
        assertEquals(
            listOf(
                ByLineRun(line = 1, endLine = 2, runtimeLine = 2),
                ByLineRun(line = 5, endLine = 5, runtimeLine = 1),
            ),
            runs,
        )
    }

    @Test
    fun `files with no mapped line are dropped rather than sent empty`() {
        val mapped = ByGeneratedFile("a.by", "a.py", listOf(null, 0))
        val prelude = ByGeneratedFile("b.by", "b.py", listOf(null, null))
        assertEquals(listOf("a.by"), ByLineMapping.invert(listOf(mapped, prelude)).map { it.source })
    }

    /** Gson leaves absent keys null regardless of the Kotlin defaults; nothing here may throw. */
    @Test
    fun `files with missing paths or no line list are dropped`() {
        val files = listOf(
            ByGeneratedFile(source = null, generated = "a.py", lines = listOf(0)),
            ByGeneratedFile(source = "b.by", generated = null, lines = listOf(0)),
            ByGeneratedFile(source = "c.by", generated = "c.py", lines = null),
        )
        assertTrue(ByLineMapping.invert(files).isEmpty())
    }

    @Test
    fun `a mapping becomes one request naming the generated file as the runtime source`() {
        val request = ByFileMapping("/abs/demo.by", "/tmp/x/demo.py", listOf(ByLineRun(1, 4, 4))).toRequest()
        assertEquals("/abs/demo.by", request.source.path)
        assertEquals(
            listOf(PydevdSourceMap(1, 4, DapSourceRef("/tmp/x/demo.py"), 4)),
            request.pydevdSourceMaps,
        )
    }
}
