package dev.basedpython.pycharm.debug

/**
 * One `pydevdSourceMap` entry: `.by` lines [line]..[endLine] map to generated lines starting at
 * [runtimeLine], one for one. All three are 1-based, which is what the protocol wants.
 *
 * A *run* rather than a single line because that is the shape pydevd's `SourceMappingEntry`
 * actually models (`runtime_line + (lineno - line)`), and because the transpiled output is mostly
 * line-for-line: a whole file typically collapses to a handful of runs, one per point where the
 * emitted code grew.
 */
data class ByLineRun(val line: Int, val endLine: Int, val runtimeLine: Int)

/** The runs for one `.by` file, ready to be sent as a single `setPydevdSourceMap` request. */
data class ByFileMapping(val source: String, val generated: String, val runs: List<ByLineRun>)

/**
 * Turns `_by_sourcemap.py`'s generated-line → `.by`-line table into the `.by`-line → generated-line
 * runs that `setPydevdSourceMap` expects.
 *
 * The inversion is not a reversal. The forward table is a total function from generated lines
 * (dense, 0-based, `null` for prelude) to `.by` lines; the inverse is a *relation*, because one
 * `.by` line routinely becomes several generated ones — `data class Point:` emits
 * `@dataclass(slots=True)` and `class Point:`. Each `.by` line is therefore pinned to the **first**
 * generated line that claims it, which is where the statement starts and so where a breakpoint
 * belongs. Consecutive `.by` lines whose first generated lines are also consecutive coalesce into
 * one run; every point where the emitted code gained a line starts a new one.
 */
object ByLineMapping {

    /**
     * [ByGeneratedFile]s with at least one mapped line, inverted into [ByFileMapping]s.
     *
     * A file missing either path, or with nothing but prelude, is dropped: an entry with no runs
     * would only tell pydevd to forget whatever mapping that file already had.
     */
    fun invert(files: List<ByGeneratedFile>): List<ByFileMapping> =
        files.mapNotNull { file ->
            val source = file.source ?: return@mapNotNull null
            val generated = file.generated ?: return@mapNotNull null
            val runs = invertLines(file.lines.orEmpty())
            if (runs.isEmpty()) null else ByFileMapping(source, generated, runs)
        }

    /**
     * [lines] is indexed by 0-based generated line and holds the 0-based `.by` line, or `null`.
     * The returned runs are 1-based and sorted by [ByLineRun.line].
     *
     * A `.by` line named by more than one generated line keeps the lowest; a generated line naming
     * a `.by` line already claimed by an earlier generated line is dropped. Prelude (`null`) is
     * simply absent from the result — nothing in the `.by` source corresponds to it.
     */
    fun invertLines(lines: List<Int?>): List<ByLineRun> {
        // .by line (0-based) -> first generated line (0-based) that produced it.
        val firstGenerated = sortedMapOf<Int, Int>()
        lines.forEachIndexed { generated, source ->
            if (source != null && source >= 0) firstGenerated.putIfAbsent(source, generated)
        }

        val runs = mutableListOf<ByLineRun>()
        var startSource = -1
        var startGenerated = -1
        var previousSource = -1
        var previousGenerated = -1

        fun flush() {
            if (startSource >= 0) {
                runs += ByLineRun(startSource + 1, previousSource + 1, startGenerated + 1)
            }
        }

        for ((source, generated) in firstGenerated) {
            val continues = startSource >= 0 &&
                source == previousSource + 1 &&
                generated == previousGenerated + 1
            if (!continues) {
                flush()
                startSource = source
                startGenerated = generated
            }
            previousSource = source
            previousGenerated = generated
        }
        flush()
        return runs
    }
}
