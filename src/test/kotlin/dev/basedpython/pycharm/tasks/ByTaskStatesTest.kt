package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How a verdict on one task colours the rows above it. */
class ByTaskStatesTest {

    private val file = checkNotNull(
        PreCommitTasks.parse(
            """
            repos:
              - repo: local
                hooks:
                  - id: fast
                  - id: slow
            """.trimIndent(),
            ".pre-commit-config.yaml",
        ),
    )

    private val repo get() = file.children.single()
    private val fast get() = repo.children[0]
    private val slow get() = repo.children[1]

    @Test
    fun `nothing run is nothing shown`() {
        val states = ByTaskStates.of(listOf(file), emptyMap())

        assertEquals(ByTaskState.NOT_RUN, states[file])
        assertEquals(ByTaskState.NOT_RUN, states[fast])
    }

    @Test
    fun `one failure is what the rows above it show`() {
        val states = ByTaskStates.of(
            listOf(file),
            mapOf(fast.key to ByTaskState.PASSED, slow.key to ByTaskState.FAILED),
        )

        assertEquals(ByTaskState.PASSED, states[fast])
        assertEquals(ByTaskState.FAILED, states[slow])
        assertEquals(ByTaskState.FAILED, states[repo])
        assertEquals(ByTaskState.FAILED, states[file])
    }

    /** Green on a collapsed row has to mean everything under it passed. */
    @Test
    fun `one hook passing does not turn its file green`() {
        val states = ByTaskStates.of(listOf(file), mapOf(fast.key to ByTaskState.PASSED))

        assertEquals(ByTaskState.NOT_RUN, states[file])
    }

    @Test
    fun `everything passing does`() {
        val states = ByTaskStates.of(
            listOf(file),
            mapOf(fast.key to ByTaskState.PASSED, slow.key to ByTaskState.PASSED),
        )

        assertEquals(ByTaskState.PASSED, states[file])
    }

    /**
     * A group is frequently run as one process — `pre-commit run --all-files` — and its own exit
     * code is a better answer than folding children that were never started individually.
     */
    @Test
    fun `a group that was run itself shows its own verdict`() {
        val states = ByTaskStates.of(listOf(file), mapOf(file.key to ByTaskState.PASSED))

        assertEquals(ByTaskState.PASSED, states[file])
        assertEquals(ByTaskState.NOT_RUN, states[fast])
    }

    @Test
    fun `a run in progress outranks a pass and not a failure`() {
        assertEquals(
            ByTaskState.RUNNING,
            ByTaskState.worst(listOf(ByTaskState.PASSED, ByTaskState.RUNNING)),
        )
        assertEquals(
            ByTaskState.FAILED,
            ByTaskState.worst(listOf(ByTaskState.RUNNING, ByTaskState.FAILED)),
        )
        assertEquals(ByTaskState.NOT_RUN, ByTaskState.worst(emptyList()))
    }
}
