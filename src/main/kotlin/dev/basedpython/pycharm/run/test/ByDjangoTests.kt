package dev.basedpython.pycharm.run.test

/**
 * How a test is named to `manage.py test`, which takes dotted labels rather than pytest's node ids.
 *
 * A run's targets stay pytest node ids whatever runs them (`polls/tests/test_views.py::QuestionTests::
 * test_create`), as the gutter, the node view and *Rerun Failed Tests* write them, so that every one of
 * them names a test the same way; a Django run turns them into labels only when it builds its command
 * line. A label is the module, resolved the way django's runner imports it — from the directory
 * `manage.py` is in, which is first on `sys.path` — then the class and the test.
 */
internal object ByDjangoTests {

    /** `-v 2`: one line per test, which is what the tree is built from when no reporter speaks. */
    val ARGUMENTS: List<String> = listOf("test", "-v", "2")

    /**
     * The label of [nodeId], whose path is relative to [base] — `""`, or the directory `manage.py` is in
     * with a trailing `/` — or null when the path is not a `.py` or a `.by` under it: such a module
     * cannot be named to the runner. A `.by` is a run in place's, which imports it under the same
     * dotted name.
     */
    fun label(nodeId: String, base: String): String? {
        val parts = nodeId.split("::")
        val path = parts.first()
        val suffix = listOf(PY, BY).firstOrNull { path.endsWith(it) }
        if (!path.startsWith(base) || suffix == null) return null
        val module = path.removePrefix(base).removeSuffix(suffix).removeSuffix(INIT).trim('/')
        if (module.isEmpty() || module.split('/').any { !it.isIdentifier() }) return null
        return (listOf(module.replace('/', '.')) + parts.drop(1)).joinToString(".")
    }

    /**
     * The path a node id names the file of [module] by, relative to the same [base] as [label]'s, with
     * [extension]: `.py` in a staged tree and for a plain run, and in place whichever the project holds.
     */
    fun moduleFile(module: String, base: String, extension: String = PY): String = base + module.replace('.', '/') + extension

    private fun String.isIdentifier(): Boolean =
        isNotEmpty() && (first().isLetter() || first() == '_') && all { it.isLetterOrDigit() || it == '_' }

    private const val PY = ".py"
    const val BY = ".by"
    private const val INIT = "/__init__"
}
