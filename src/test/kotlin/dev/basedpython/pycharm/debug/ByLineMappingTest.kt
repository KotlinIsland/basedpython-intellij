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
        // .by line 3 claims two generated lines; it pins to the second, so the run breaks before it.
        val lines = listOf(0, 1, 2, 2, 3, 4)
        assertEquals(
            listOf(
                ByLineRun(line = 1, endLine = 2, runtimeLine = 1),
                ByLineRun(line = 3, endLine = 5, runtimeLine = 4),
            ),
            ByLineMapping.invertLines(lines),
        )
    }

    /**
     * The bug this rule exists for, from a real `def f(a = [])`:
     *
     * ```
     * gen 2  def f(a = _MISSING):   .by 1
     * gen 3      if a is _MISSING:  .by 2
     * gen 4          a = []         .by 2
     * gen 5      a.append(1)        .by 2
     * ```
     *
     * Pinning `.by` 2 to generated line 3 stopped the debugger on the guard, where `a` is still the
     * sentinel and reads `<object object at 0x…>`. Pinning to 5 stops on `a.append(1)` with `a == []`.
     */
    @Test
    fun `a default-argument guard does not steal the breakpoint from the statement`() {
        val lines = listOf(null, 0, 1, 1, 1, 2, 3, 4, 5, 6)
        val runs = ByLineMapping.invertLines(lines)
        val forByLine2 = runs.single { it.line <= 2 && 2 <= it.endLine }
        assertEquals(5, forByLine2.runtimeLine + (2 - forByLine2.line))
    }

    /**
     * The extra generated lines are prologue the transpiler emits ahead of the user's statement, so
     * the statement itself is the last of them.
     */
    @Test
    fun `a by line claimed by several generated lines keeps the last`() {
        val runs = ByLineMapping.invertLines(listOf(null, 7, 7, 7))
        assertEquals(listOf(ByLineRun(line = 8, endLine = 8, runtimeLine = 4)), runs)
    }

    @Test
    fun `a gap in the by lines starts a new run`() {
        // .by line 2 (index 1) produced nothing — a comment that was dropped, say.
        val lines: List<Int?> = listOf(0, 2, 3)
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

    /**
     * The real thing, captured from `by run` 0.0.1a9 on a file that starts with a `data class`.
     *
     * 83 lines of prelude, then `data class Point:` claiming two generated lines, then the rest
     * one for one — which is the whole file in exactly two runs. Verified live: with these runs
     * registered, every breakpoint lands on the right generated line and frames come back as
     * `demo.by`.
     */
    @Test
    fun `the map by run actually emits collapses to a single run`() {
        val lines = List(83) { null } + listOf(0, 0) + (1..15).toList()
        // `data class Point:` claims generated 84 and 85; pinning .by 1 to 85 makes the whole file
        // one uninterrupted run, and puts a breakpoint on `class Point:` rather than its decorator.
        assertEquals(
            listOf(ByLineRun(line = 1, endLine = 16, runtimeLine = 85)),
            ByLineMapping.invertLines(lines),
        )
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
