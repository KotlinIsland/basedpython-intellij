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
 * Matching lives in [findConsoleLinks]; this resolves what it finds and builds the hyperlinks.
 * Recognised shapes:
 *   - `src/main.by:12:5`, `out/main.py:7`, `/abs/path/foo.by`
 *   - `File "/abs/path/main.by", line 12, in main` — a traceback frame, which `by run` already
 *     rewrites onto `.by` sources
 *   - `tests/test_math.py:8` from pytest, resolved to the `tests/test_math.by` it came from
 *
 * Each match is resolved to a [VirtualFile] (absolute first, then relative to the project base dir)
 * and turned into an [OpenFileHyperlinkInfo] pointing at the (0-based) line/column.
 *
 * Registered through [ByConsoleFilterProvider] via the `consoleFilterProvider` extension point.
 */
class ByConsoleFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        // offset of the start of this `line` within the whole document
        val lineStart = entireLength - line.length

        val items = findConsoleLinks(line).mapNotNull { link ->
            val vFile = resolve(link.path) ?: return@mapNotNull null
            // line/col are 1-based in `by` output; OpenFileHyperlinkInfo expects 0-based.
            val docLine = ((link.line ?: 1) - 1).coerceAtLeast(0)
            val docCol = ((link.column ?: 1) - 1).coerceAtLeast(0)
            Filter.ResultItem(
                lineStart + link.start,
                lineStart + link.end,
                OpenFileHyperlinkInfo(project, vFile, docLine, docCol),
            )
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    /**
     * Resolve [path] absolutely, then relative to the project base dir — and failing both, as the
     * `.by` source a transpiled `.py` came from.
     *
     * That last step is what makes a pytest failure clickable: the suite runs against the
     * transpiled tree, so pytest reports `tests/test_math.py:8` for a project that only contains
     * `tests/test_math.by`. Trying the path as written first keeps real generated output under
     * `out/` linking to itself.
     */
    private fun resolve(path: String): VirtualFile? =
        resolveExact(path) ?: byCounterpart(path)?.let(::resolveExact)

    private fun resolveExact(path: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        val asPath = runCatching { Paths.get(path) }.getOrNull() ?: return null
        if (asPath.isAbsolute) {
            return lfs.findFileByNioFile(asPath)
        }
        val base = project.basePath ?: return null
        val resolved = runCatching { Paths.get(base).resolve(path).normalize() }.getOrNull() ?: return null
        return lfs.findFileByNioFile(resolved)
    }
}

/** Supplies [ByConsoleFilter] to every run/debug console. Registered via `<consoleFilterProvider>`. */
class ByConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(ByConsoleFilter(project))
}
