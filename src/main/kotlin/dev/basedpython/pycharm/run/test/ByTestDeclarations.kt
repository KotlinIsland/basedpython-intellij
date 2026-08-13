package dev.basedpython.pycharm.run.test

/**
 * A declaration and the classes enclosing it: `TestGroup::test_add` is
 * `ByDeclarationPath(["TestGroup", "test_add"], isClass = false)`.
 *
 * [symbols] is the `::`-separated tail of a pytest node id, which is what makes this the shared
 * currency between the run-configuration producer, the gutter markers and the collected index.
 */
internal data class ByDeclarationPath(val symbols: List<String>, val isClass: Boolean)

/**
 * Reads declarations out of `.by` source text, by line.
 *
 * Textual on purpose, for the reason given in
 * [dev.basedpython.pycharm.run.test.tree.ByTestLocations]: the PSI for `.by` is one leaf per token,
 * with no declarations to walk.
 *
 * Deliberately *name-agnostic* — it reports `def helper` and `class Foo` as readily as `def test_x`
 * — because whether a declaration is a test is not a property of its name. pytest's own answer is
 * whatever `--collect-only` returned, and only [isConventionalTest] guesses from the name, for the
 * cases where nothing has been collected yet.
 */
internal object ByTestDeclarations {

    /**
     * The declaration on [line], or null when that line declares nothing.
     *
     * @param lineText text of a line by index, which the caller reads from wherever it has it
     *   (a `Document` without materialising every line, a `String` already split)
     * @param lineCount how many lines there are, bounding the walk for an enclosing class
     */
    fun declarationAt(lineText: (Int) -> String, lineCount: Int, line: Int): ByDeclarationPath? {
        val text = lineText(line)
        CLASS.matchEntire(text)?.let { match ->
            val indent = match.groupValues[1].length
            val name = match.groupValues[2]
            return ByDeclarationPath(enclosingClasses(lineText, line, indent) + name, isClass = true)
        }
        val match = DEF.matchEntire(text) ?: return null
        val indent = match.groupValues[1].length
        val name = match.groupValues[3]
        return ByDeclarationPath(enclosingClasses(lineText, line, indent) + name, isClass = false)
    }

    /**
     * True when [path] is named the way pytest collects by default: a `test_…` function, or a
     * `Test…` class.
     *
     * The fallback for "nothing has been collected yet", and only that: a project that configures
     * `python_functions` differently has tests this says no to, and `--collect-only` says yes to.
     */
    fun isConventionalTest(path: ByDeclarationPath): Boolean {
        val leaf = path.symbols.lastOrNull() ?: return false
        return if (path.isClass) leaf.startsWith(CLASS_PREFIX) else leaf.startsWith(FUNCTION_PREFIX)
    }

    /**
     * The classes enclosing a declaration indented by [indent], outermost first.
     *
     * Walks up for each strictly-less-indented `class`, so a method in a class nested in a class
     * comes back as both. Indentation is the only structure `.by` has here, and the same rule
     * Python itself applies.
     */
    private fun enclosingClasses(lineText: (Int) -> String, line: Int, indent: Int): List<String> {
        if (indent == 0) return emptyList()
        val classes = ArrayDeque<String>()
        var enclosing = indent
        for (above in line - 1 downTo 0) {
            val match = CLASS.matchEntire(lineText(above)) ?: continue
            val classIndent = match.groupValues[1].length
            if (classIndent >= enclosing) continue
            classes.addFirst(match.groupValues[2])
            enclosing = classIndent
            if (enclosing == 0) break
        }
        return classes.toList()
    }

    /** `    def name(` / `    async def name(`; group 1 is the indent, group 3 the name. */
    private val DEF = Regex("""^([ \t]*)(async[ \t]+)?def[ \t]+(\w+)[ \t]*\(.*$""")

    /** `    class Name:` / `    class Name(Base):`; group 1 is the indent, group 2 the name. */
    private val CLASS = Regex("""^([ \t]*)class[ \t]+(\w+)[ \t]*[(:].*$""")

    private const val FUNCTION_PREFIX = "test_"
    private const val CLASS_PREFIX = "Test"
}
