package dev.basedpython.pycharm.run.test.node

/**
 * Hides tests whose last outcome the user is not interested in.
 *
 * Prunes the tree rather than skipping rows at paint time, so everything downstream stays true:
 * the counts beside a file are the tests it is showing, "run this file" runs what is under it, and
 * a directory whose tests are all filtered out disappears instead of sitting there empty.
 */
internal object ByTestFilter {

    /** Every state — what the view shows until the user narrows it. */
    val ALL: Set<ByTestState> = ByTestState.entries.toSet()

    /**
     * [root] with every test outside [visible] removed, or null when nothing is left.
     *
     * A container survives exactly as long as one of its descendants does. Errors are never
     * filtered: they are not test results, and hiding the reason a file collected nothing behind a
     * state checkbox would be a good way to lose it.
     */
    fun apply(
        root: ByTestNode,
        states: Map<ByTestNode, ByTestState>,
        visible: Set<ByTestState>,
    ): ByTestNode? {
        if (visible.containsAll(ALL)) return root
        return prune(root, states, visible)
    }

    private fun prune(
        node: ByTestNode,
        states: Map<ByTestNode, ByTestState>,
        visible: Set<ByTestState>,
    ): ByTestNode? {
        if (node.kind == ByTestNodeKind.ERROR) return node
        if (node.children.isEmpty()) {
            val state = states[node] ?: ByTestState.NOT_RUN
            return node.takeIf { state in visible }
        }
        val kept = node.children.mapNotNull { prune(it, states, visible) }
        return if (kept.isEmpty()) null else node.copy(children = kept)
    }
}
