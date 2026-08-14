package dev.basedpython.pycharm.run.test.node

import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds pytest-named `.py` files in a project.
 *
 * Asked only when a collection came back empty, to tell the two empty projects apart: one that has
 * no tests, and one whose tests pytest cannot see from where `by run` puts it. `by run` transpiles
 * `.by` files into a temp directory and runs pytest *there* (`by build` says it outright: "Transpile
 * all .by files"), so a project whose tests live in `.py` files hands pytest an empty tree — while
 * the same `pytest --collect-only` typed in the project collects them all. That is a confusing
 * enough gap to be worth naming, with one of the files as evidence.
 */
internal object ByPythonTests {

    /**
     * Relative paths of up to [limit] files under [base] that pytest would collect by name, in
     * directory order.
     *
     * Bounded on purpose: this runs after a fruitless collection to explain it, and no explanation
     * is worth walking a monorepo. Directories that never hold a project's own tests are skipped,
     * which is also what keeps a `.venv` full of third-party `test_*.py` out of the answer.
     */
    fun find(base: Path, limit: Int = 3, maxDepth: Int = MAX_DEPTH): List<String> {
        val found = ArrayList<String>(limit)
        walk(base, base, 0, limit, maxDepth, found)
        return found
    }

    /** True when [name] matches pytest's default `python_files`: `test_*.py` and `*_test.py`. */
    fun isTestFileName(name: String): Boolean =
        name.endsWith(PY) && (name.startsWith(TEST_PREFIX) || name.dropLast(PY.length).endsWith(TEST_SUFFIX))

    private fun walk(base: Path, dir: Path, depth: Int, limit: Int, maxDepth: Int, found: MutableList<String>) {
        if (found.size >= limit || depth > maxDepth) return
        val entries = try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        // Files first, so the shallowest example is the one reported.
        for (entry in entries) {
            if (found.size >= limit) return
            val name = entry.fileName?.toString() ?: continue
            if (!Files.isDirectory(entry) && isTestFileName(name)) {
                found += base.relativize(entry).toString().replace('\\', '/')
            }
        }
        for (entry in entries) {
            if (found.size >= limit) return
            val name = entry.fileName?.toString() ?: continue
            if (Files.isDirectory(entry) && name !in SKIPPED && !name.startsWith(".")) {
                walk(base, entry, depth + 1, limit, maxDepth, found)
            }
        }
    }

    /** Directories whose `test_*.py` files are never the project's own. */
    private val SKIPPED = setOf(
        "out", "build", "dist", "node_modules", "__pycache__", "venv", "site-packages", "target",
    )

    private const val MAX_DEPTH = 8
    private const val PY = ".py"
    private const val TEST_PREFIX = "test_"
    private const val TEST_SUFFIX = "_test"
}
