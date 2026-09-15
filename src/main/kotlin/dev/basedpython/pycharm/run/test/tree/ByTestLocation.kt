package dev.basedpython.pycharm.run.test.tree

/**
 * A pytest node id taken apart: the file pytest names, and the chain of names leading to the test.
 *
 * `tests/test_math.py::TestGroup::test_in_class` becomes
 * `ByTestLocation("tests/test_math.py", ["TestGroup", "test_in_class"])`.
 */
data class ByTestLocation(val file: String, val symbols: List<String>)

/** Pure half of [ByTestSources.locate]: reading a node id, which needs no project. */
object ByTestLocations {

    /**
     * Parse a `by_test://` path, or null when it names nothing resolvable.
     *
     * A node id that does not name a `.py` file is rejected: unittest reports a dotted module
     * (`mymod.MathTest`), which is not a path.
     */
    fun parse(path: String): ByTestLocation? {
        val parts = path.split("::").map { it.trim() }.filter { it.isNotEmpty() }
        val file = parts.firstOrNull() ?: return null
        if (!file.endsWith(PY_EXTENSION, ignoreCase = true)) return null
        return ByTestLocation(file = file, symbols = parts.drop(1).map(::baseName))
    }

    /**
     * The declaration name behind a pytest node name: `test_add[1-2]` is one generated case of
     * `def test_add`, and the brackets are the parameters, not part of the name.
     */
    fun baseName(nodeName: String): String = nodeName.substringBefore('[').trim()

    private const val PY_EXTENSION = ".py"
}
