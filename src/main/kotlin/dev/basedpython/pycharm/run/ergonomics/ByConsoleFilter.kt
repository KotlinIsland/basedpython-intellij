package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

/**
 * Console filter that makes `.by` and transpiled `.py` file paths in `by`/`buff` output clickable.
 *
 * Matches paths ending in `.by` or `.py` (the transpiled output under `out/`), optionally followed
 * by `:line` and `:col` suffixes, e.g.:
 *   - `src/main.by:12:5`
 *   - `out/main.py:7`
 *   - `/abs/path/foo.by`
 *
 * Each match is resolved to a [VirtualFile] (absolute first, then relative to the project base dir)
 * and turned into an [OpenFileHyperlinkInfo] pointing at the (0-based) line/column.
 *
 * Registered through [ByConsoleFilterProvider] via the `consoleFilterProvider` extension point.
 */
class ByConsoleFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val items = mutableListOf<Filter.ResultItem>()
        // offset of the start of this `line` within the whole document
        val lineStart = entireLength - line.length

        val matcher = PATH_PATTERN.matcher(line)
        while (matcher.find()) {
            val path = matcher.group(GROUP_PATH) ?: continue
            val vFile = resolve(path) ?: continue

            // line/col are 1-based in `by` output; OpenFileHyperlinkInfo expects 0-based.
            val reportedLine = matcher.group(GROUP_LINE)?.toIntOrNull()
            val reportedCol = matcher.group(GROUP_COL)?.toIntOrNull()
            val docLine = ((reportedLine ?: 1) - 1).coerceAtLeast(0)
            val docCol = ((reportedCol ?: 1) - 1).coerceAtLeast(0)

            val info = OpenFileHyperlinkInfo(project, vFile, docLine, docCol)
            items.add(
                Filter.ResultItem(
                    lineStart + matcher.start(),
                    lineStart + matcher.end(),
                    info,
                )
            )
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /** Resolve [path] absolutely, then relative to the project base dir. */
    private fun resolve(path: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        val asPath = runCatching { Paths.get(path) }.getOrNull() ?: return null
        if (asPath.isAbsolute) {
            return lfs.findFileByNioFile(asPath)
        }
        val base = project.basePath ?: return null
        val resolved = runCatching { Paths.get(base).resolve(path).normalize() }.getOrNull() ?: return null
        return lfs.findFileByNioFile(resolved)
    }

    companion object {
        // group indexes match the named groups below
        private const val GROUP_PATH = 1
        private const val GROUP_LINE = 2
        private const val GROUP_COL = 3

        /**
         * A file path token ending in `.by` or `.py` with an optional `:line[:col]` suffix.
         * Path chars exclude whitespace and the `:` used as the line/col separator, but allow
         * `/`, `\`, `.`, `-`, `_` and alphanumerics so both POSIX and Windows-ish paths match.
         */
        private val PATH_PATTERN =
            Regex("""([\w./\\\-]+\.(?:by|py))(?::(\d+))?(?::(\d+))?""").toPattern()
    }
}

/** Supplies [ByConsoleFilter] to every run/debug console. Registered via `<consoleFilterProvider>`. */
class ByConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(ByConsoleFilter(project))
}
