package dev.basedpython.pycharm.tasks

import java.util.IdentityHashMap

/** The states of a whole task tree, computed once per repaint. */
internal object ByTaskStates {

    /**
     * A state for every node of [roots], from the per-task [outcomes] keyed by [ByTaskNode.key].
     *
     * A node's own verdict wins over its children's. That is the rule this tree needs and the test
     * view does not: here a group is often runnable itself — `lefthook run pre-commit` is one
     * process with one exit code — and folding its children over that would report a whole hook
     * that just passed as "not run", because the four commands inside it were never started
     * individually and never will be.
     *
     * Without one of its own, a group folds its children with [ByTaskState.worst], so a
     * `.pre-commit-config.yaml` whose hooks were each run turns red on the first failure.
     *
     * One bottom-up pass rather than per row: a renderer asks about every visible row, and
     * re-folding a subtree each time turns a repaint into O(rows × subtree). Keyed by identity
     * because [ByTaskNode] is a data class whose hash walks its whole subtree — and the renderer
     * looks up the very instances walked here, both coming from the same tree.
     */
    fun of(roots: List<ByTaskNode>, outcomes: Map<String, ByTaskState>): Map<ByTaskNode, ByTaskState> {
        val states: MutableMap<ByTaskNode, ByTaskState> = IdentityHashMap()
        roots.forEach { fold(it, outcomes, states) }
        return states
    }

    private fun fold(
        node: ByTaskNode,
        outcomes: Map<String, ByTaskState>,
        states: MutableMap<ByTaskNode, ByTaskState>,
    ): ByTaskState {
        val children = node.children.map { fold(it, outcomes, states) }
        val state = outcomes[node.key]
            ?: if (children.isEmpty()) ByTaskState.NOT_RUN else ByTaskState.worst(children)
        states[node] = state
        return state
    }
}
