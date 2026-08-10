package dev.basedpython.pycharm.run.ergonomics

/**
 * One clickable file reference found in console output: where it sits in the line, what it points
 * at, and the 1-based position it names (null when the output gave none).
 */
internal data class ByConsoleLink(
    val start: Int,
    val end: Int,
    val path: String,
    val line: Int?,
    val column: Int?,
)

/**
 * Finds file references in one line of `by` / `buff` / program output.
 *
 * Two shapes, because the tools that produce them are different programs:
 *
 *  - `by`'s own diagnostics and pytest failures put the position after a colon —
 *    `src/main.by:12:5`, `tests/test_math.py:8: AssertionError`.
 *  - A Python traceback puts it in prose — `File "/abs/main.by", line 12, in main`. `by run`'s
 *    generated `_by_runner.py` rewrites tracebacks to `.by` paths and `.by` lines, so these are
 *    the frames a user most wants to click, and matching only the first shape sent every one of
 *    them to line 1 of the file.
 *
 * The traceback form is matched first and its span suppresses any colon-form match inside it, so a
 * quoted path is not also reported as a bare one.
 */
internal fun findConsoleLinks(text: String): List<ByConsoleLink> {
    val links = mutableListOf<ByConsoleLink>()

    val traceback = TRACEBACK_PATTERN.findAll(text).toList()
    for (match in traceback) {
        val path = match.groups[1] ?: continue
        links += ByConsoleLink(
            start = path.range.first,
            end = path.range.last + 1,
            path = path.value,
            line = match.groupValues[2].toIntOrNull(),
            column = null,
        )
    }

    val covered = traceback.map { it.range }
    for (match in PATH_PATTERN.findAll(text)) {
        val path = match.groups[1] ?: continue
        if (covered.any { path.range.first in it }) continue
        links += ByConsoleLink(
            start = match.range.first,
            end = match.range.last + 1,
            path = path.value,
            line = match.groupValues[2].toIntOrNull(),
            column = match.groupValues[3].toIntOrNull(),
        )
    }

    return links.sortedBy { it.start }
}

/**
 * The `.by` source a transpiled path corresponds to, or null when [path] is not a `.py`.
 *
 * `by run` transpiles into a temp directory preserving relative paths, so pytest reports failures
 * against `tests/test_math.py` for a project that only ever had `tests/test_math.by`. Trying the
 * path as written first means real generated output under `out/` still links to itself.
 */
internal fun byCounterpart(path: String): String? =
    if (path.endsWith(PY_EXTENSION, ignoreCase = true)) {
        path.dropLast(PY_EXTENSION.length) + BY_EXTENSION
    } else {
        null
    }

private const val PY_EXTENSION = ".py"
private const val BY_EXTENSION = ".by"

/**
 * A file path token ending in `.by` or `.py` with an optional `:line[:col]` suffix. Path chars
 * exclude whitespace and the `:` used as the separator, but allow `/`, `\`, `.`, `-`, `_` and
 * alphanumerics so both POSIX and Windows-ish paths match.
 */
private val PATH_PATTERN = Regex("""([\w./\\\-]+\.(?:by|py))(?::(\d+))?(?::(\d+))?""")

/** CPython's traceback frame header, which quotes the path and spells the line out in words. */
private val TRACEBACK_PATTERN = Regex("""File "([^"]+\.(?:by|py))", line (\d+)""")
