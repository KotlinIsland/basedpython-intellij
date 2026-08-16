package dev.basedpython.pycharm.run.test.node

import java.util.IdentityHashMap

/**
 * What the last run said about a test.
 *
 * Ordered worst-first *deliberately*: [worst] folds a parent's children by taking the minimum, so
 * one failure in a file is what the file shows. That is the rule every test view uses, and the one
 * users read a collapsed tree by — a green file means nothing under it needs looking at.
 */
internal enum class ByTestState {
    /** Reported failed, or errored: the same thing to look at. */
    FAILED,

    /** Started and has not reported yet. */
    RUNNING,

    /** Skipped, xfailed, or otherwise not executed by choice. */
    SKIPPED,

    /**
     * Collected, never run — the state every node starts in.
     *
     * Ahead of [PASSED] on purpose, so a file where one test was run and passed reads as not run
     * rather than green. Green on a file has to mean every test in it passed, or a collapsed tree
     * cannot be trusted; running one test out of ten says nothing about the other nine.
     */
    NOT_RUN,

    /** Reported passed — and, for a parent, so did everything under it. */
    PASSED,
    ;

    companion object {
        /** The state a parent shows for [children]: the worst any of them is in. */
        fun worst(children: Iterable<ByTestState>): ByTestState =
            children.minOrNull() ?: NOT_RUN
    }
}

/** The states of a whole tree, computed once per repaint. */
internal object ByTestStates {

    /**
     * A state for every node of [root], from the per-test [outcomes] keyed by pytest node id.
     *
     * Leaves take their own outcome; everything above folds its children with [ByTestState.worst].
     * A parametrized test is a parent here as everywhere else: `test_add` is red when any of its
     * cases is, and reports nothing of its own.
     *
     * Computed in one bottom-up pass rather than per row, because a renderer asks about every
     * visible row and re-folding a subtree each time turns a repaint into O(rows × subtree).
     *
     * Keyed by identity, not equality: [ByTestNode] is a data class, so hashing one walks its whole
     * subtree — a [HashMap] here would cost more than the fold it is meant to save. The renderer
     * looks up the very instances this walked, since both come from the same tree.
     */
    fun of(root: ByTestNode, outcomes: Map<String, ByTestState>): Map<ByTestNode, ByTestState> {
        val states: MutableMap<ByTestNode, ByTestState> = IdentityHashMap()
        fold(root, outcomes, states)
        return states
    }

    private fun fold(
        node: ByTestNode,
        outcomes: Map<String, ByTestState>,
        states: MutableMap<ByTestNode, ByTestState>,
    ): ByTestState {
        val state = if (node.children.isEmpty()) {
            node.target?.let { outcomes[it] } ?: ByTestState.NOT_RUN
        } else {
            ByTestState.worst(node.children.map { fold(it, outcomes, states) })
        }
        states[node] = state
        return state
    }
}
