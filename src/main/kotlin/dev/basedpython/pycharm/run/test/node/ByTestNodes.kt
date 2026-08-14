package dev.basedpython.pycharm.run.test.node

/** What a [ByTestNode] stands for, which decides its icon and what running it means. */
internal enum class ByTestNodeKind {
    /** The invisible top of the tree; running it runs every test in the project. */
    ROOT,

    /** A directory holding tests, possibly several path segments deep — see [ByTestNodes.build]. */
    DIRECTORY,

    /** One test file, named as its `.by` source. */
    FILE,

    /** A `class Test…` grouping tests inside a file. */
    CLASS,

    /** A `def test_…`. Parametrized ones have a [ByTestNodeKind.CASE] child per generated case. */
    TEST,

    /** One generated case of a parametrized test, e.g. `[1-2]`. */
    CASE,

    /** Something pytest or `by` refused to collect; carries the reason in [ByTestNode.detail]. */
    ERROR,
}

/**
 * A node of the collected test tree.
 *
 * @param name what the user reads — a file is named as its `.by` source, a case as just its
 *   bracketed parameters
 * @param target the pytest target that runs exactly this node, as pytest reported it; null for the
 *   root (which is "everything") and for an error with no file
 * @param source which pytest run found it, which decides how [target] is read and how it is run:
 *   a [ByTestSource.TRANSPILED] path stands for a `.by` source, a [ByTestSource.PYTHON] one is the
 *   file itself
 * @param detail a grey (or red, for [ByTestNodeKind.ERROR]) suffix
 */
internal data class ByTestNode(
    val name: String,
    val kind: ByTestNodeKind,
    val target: String?,
    val children: List<ByTestNode> = emptyList(),
    val detail: String? = null,
    val source: ByTestSource = ByTestSource.TRANSPILED,
) {
    /**
     * How many runnable tests are at or under this node.
     *
     * A parametrized test counts once per case, matching pytest's own footer: its four
     * `test_add[…]` cases are four tests, and the `test_add` node grouping them is not a fifth.
     */
    val testCount: Int
        get() = when {
            children.isNotEmpty() -> children.sumOf { it.testCount }
            kind == ByTestNodeKind.TEST || kind == ByTestNodeKind.CASE -> 1
            else -> 0
        }
}

/**
 * Turns the flat node ids of [ByPytestCollect] into the tree the view shows.
 *
 * pytest reports one line per test — `tests/unit/test_math.py::TestGroup::test_add[1-2]` — which
 * carries the whole hierarchy in it: directories, the file, any enclosing classes, the function,
 * and the parameters it was generated with. Collection order is preserved at every level, so tests
 * appear in the order they are written rather than alphabetically.
 */
internal object ByTestNodes {

    /** The tree for one collection, errors included as [ByTestNodeKind.ERROR] nodes at the end. */
    fun build(collection: ByCollection, rootName: String = "Tests"): ByTestNode {
        val root = Node(rootName, ByTestNodeKind.ROOT, target = null, source = ByTestSource.TRANSPILED)
        for (node in collection.nodes) insert(root, node)
        val tests = root.freeze().children.map(::compress)
        val errors = collection.errors.map {
            ByTestNode(
                name = it.target?.let(::sourceTarget) ?: it.message,
                kind = ByTestNodeKind.ERROR,
                target = it.target,
                detail = if (it.target == null) null else it.message,
            )
        }
        return ByTestNode(rootName, ByTestNodeKind.ROOT, target = null, children = tests + errors)
    }

    /**
     * The `.by` source form of a pytest target: `tests/test_x.py::test_a` → `tests/test_x.by::test_a`.
     *
     * The inverse of [dev.basedpython.pycharm.run.test.ByPytest.nodeId], and needed for the same
     * reason: pytest only ever sees the transpiled tree, while both navigation and the run
     * configuration are written in terms of the sources. Only the file part is touched — everything
     * after `::` is a name, not a path.
     */
    fun sourceTarget(target: String): String {
        val separator = target.indexOf("::")
        val path = if (separator < 0) target else target.substring(0, separator)
        val suffix = if (separator < 0) "" else target.substring(separator)
        if (!path.endsWith(PY_EXTENSION)) return target
        return path.dropLast(PY_EXTENSION.length) + BY_EXTENSION + suffix
    }

    /**
     * The path a node id names in the project: the `.by` it was transpiled from, or the `.py`
     * itself when plain pytest collected it.
     */
    fun sourcePath(node: ByCollectedNode): String {
        val path = node.nodeId.substringBefore("::")
        return if (node.source == ByTestSource.TRANSPILED) sourceTarget(path) else path
    }

    /** Adds one collected node to the tree, creating whatever levels it needs. */
    private fun insert(root: Node, collected: ByCollectedNode) {
        val nodeId = collected.nodeId
        val source = collected.source
        val separator = nodeId.indexOf("::")
        val path = if (separator < 0) nodeId else nodeId.substring(0, separator)
        val names = if (separator < 0) emptyList() else nodeId.substring(separator + 2).split("::")

        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return

        var current = root
        for ((index, segment) in segments.withIndex()) {
            val target = current.target?.let { "$it/$segment" } ?: segment
            val isFile = index == segments.lastIndex
            current = current.child(
                key = segment,
                // A transpiled file is named as the `.by` the user edits; a `.py` collected in the
                // project already is the file they edit.
                name = if (isFile && source == ByTestSource.TRANSPILED) sourceTarget(segment) else segment,
                kind = if (isFile) ByTestNodeKind.FILE else ByTestNodeKind.DIRECTORY,
                target = target,
                source = source,
            )
        }

        for ((index, name) in names.withIndex()) {
            if (index < names.lastIndex) {
                current = current.child(name, name, ByTestNodeKind.CLASS, "${current.target}::$name", source)
                continue
            }
            // The last name is the function, and carries the parameters of a generated case:
            // `test_add[1-2]` is one case of `def test_add`, and the cases hang off it so that a
            // parametrized test is one line in the tree until it is expanded.
            val function = name.substringBefore('[')
            val case = name.removePrefix(function)
            val test = current.child(function, function, ByTestNodeKind.TEST, "${current.target}::$function", source)
            if (case.isNotEmpty()) test.child(case, case, ByTestNodeKind.CASE, "${test.target}$case", source)
        }
    }

    /**
     * Collapses a directory that only contains another directory into one node: `tests` holding
     * only `unit` becomes `tests/unit`.
     *
     * The same thing the Project view calls compacting middle packages, and worth the few lines
     * here: a project whose tests live at `src/test/python/unit` would otherwise cost four clicks
     * to open, none of which is a choice.
     */
    private fun compress(node: ByTestNode): ByTestNode {
        val children = node.children.map(::compress)
        val only = children.singleOrNull()
        if (node.kind == ByTestNodeKind.DIRECTORY && only != null && only.kind == ByTestNodeKind.DIRECTORY) {
            return only.copy(name = "${node.name}/${only.name}")
        }
        return node.copy(children = children)
    }

    /** Mutable node used while building; [freeze] turns it into the immutable [ByTestNode]. */
    private class Node(
        val name: String,
        val kind: ByTestNodeKind,
        val target: String?,
        val source: ByTestSource,
    ) {
        private val children = LinkedHashMap<String, Node>()

        fun child(key: String, name: String, kind: ByTestNodeKind, target: String, source: ByTestSource): Node =
            children.getOrPut(key) { Node(name, kind, target, source) }

        fun freeze(): ByTestNode =
            ByTestNode(name, kind, target, children.values.map { it.freeze() }, source = source)
    }

    private const val PY_EXTENSION = ".py"
    private const val BY_EXTENSION = ".by"
}
