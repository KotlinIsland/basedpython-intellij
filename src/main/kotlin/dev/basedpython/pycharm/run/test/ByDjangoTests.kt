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
     * with a trailing `/` — or null when the path is not a `.py` under it: such a module cannot be
     * named to the runner.
     */
    fun label(nodeId: String, base: String): String? {
        val parts = nodeId.split("::")
        val path = parts.first()
        if (!path.startsWith(base) || !path.endsWith(PY)) return null
        val module = path.removePrefix(base).removeSuffix(PY).removeSuffix(INIT).trim('/')
        if (module.isEmpty() || module.split('/').any { !it.isIdentifier() }) return null
        return (listOf(module.replace('/', '.')) + parts.drop(1)).joinToString(".")
    }

    /** The path a node id names the file of [module] by, relative to the same [base] as [label]'s. */
    fun moduleFile(module: String, base: String): String = base + module.replace('.', '/') + PY

    private fun String.isIdentifier(): Boolean =
        isNotEmpty() && (first().isLetter() || first() == '_') && all { it.isLetterOrDigit() || it == '_' }

    private const val PY = ".py"
    private const val INIT = "/__init__"
}
