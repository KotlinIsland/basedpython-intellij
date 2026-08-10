package dev.basedpython.pycharm.run.test.tree

/**
 * A pytest node id taken apart: which `.by` file it lives in, and the chain of declarations
 * leading to it.
 *
 * `tests/test_math.py::TestGroup::test_in_class` becomes
 * `ByTestLocation("tests/test_math.by", ["TestGroup", "test_in_class"])`.
 */
data class ByTestLocation(val file: String, val symbols: List<String>)

/**
 * Pure half of [ByTestLocator]: everything about turning a node id into a place in a `.by` file
 * that does not need a [com.intellij.openapi.project.Project].
 *
 * pytest reports node ids against the *transpiled* tree — paths relative to `by run`'s temp
 * directory, naming `.py` files. Relative paths are preserved through transpilation, so the only
 * difference from the source is the extension (see [dev.basedpython.pycharm.run.test.ByPytest]).
 */
object ByTestLocations {

    /**
     * Parse a `by_test://` path, or null when it names nothing resolvable.
     *
     * A node id that does not name a `.py` file is rejected rather than guessed at: unittest
     * reports a dotted module (`mymod.MathTest`), which is not a path, and inventing one would
     * navigate to the wrong file rather than to none.
     */
    fun parse(path: String): ByTestLocation? {
        val parts = path.split("::").map { it.trim() }.filter { it.isNotEmpty() }
        val file = parts.firstOrNull() ?: return null
        if (!file.endsWith(PY_EXTENSION, ignoreCase = true)) return null
        return ByTestLocation(
            file = file.dropLast(PY_EXTENSION.length) + BY_EXTENSION,
            symbols = parts.drop(1).map(::baseName),
        )
    }

    /**
     * The declaration name behind a pytest node name: `test_add[1-2]` is one generated case of
     * `def test_add`, and the brackets are the parameters, not part of the name.
     */
    fun baseName(nodeName: String): String = nodeName.substringBefore('[').trim()

    /**
     * Offset of the declaration named by [symbols] in [text], or null when it is not there.
     *
     * Each symbol is searched for after the previous one, so `TestGroup` then `test_in_class` finds
     * the method inside that class rather than a same-named function earlier in the file. The
     * offset points at the *name*, so navigation lands on the identifier and not on the `def`.
     *
     * Deliberately textual. The PSI for `.by` is flat — one leaf per token, no declarations to walk
     * (FEATURES.md §1) — so there is no tree to ask, and the `by` server does not answer
     * "where is this test" queries.
     */
    fun declarationOffset(text: String, symbols: List<String>): Int? {
        var from = 0
        var found: Int? = null
        for (symbol in symbols) {
            val offset = findDeclaration(text, symbol, from) ?: return found
            found = offset
            from = offset
        }
        return found
    }

    private fun findDeclaration(text: String, name: String, from: Int): Int? {
        val pattern = Regex(
            """^[ \t]*(?:async[ \t]+)?(?:def|class)[ \t]+(${Regex.escape(name)})\b""",
            RegexOption.MULTILINE,
        )
        return pattern.find(text, from)?.groups?.get(1)?.range?.first
    }

    private const val PY_EXTENSION = ".py"
    private const val BY_EXTENSION = ".by"
}
