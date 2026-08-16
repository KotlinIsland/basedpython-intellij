package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The command each row produces.
 *
 * This is the file that decides whether the view runs what it says it runs, so the expectations are
 * written out as whole argument lists rather than checked flag by flag.
 */
class ByTaskCommandsTest {

    private fun node(
        runner: ByTaskRunner,
        kind: ByTaskKind,
        id: String? = null,
        stage: String? = null,
    ) = ByTaskNode(name = id ?: "x", kind = kind, runner = runner, path = "config", id = id, stage = stage)

    @Test
    fun `pre-commit runs one hook, or all of them`() {
        assertEquals(
            listOf("run", "black", "--all-files"),
            ByTaskCommands.arguments(node(ByTaskRunner.PRE_COMMIT, ByTaskKind.HOOK, "black"), allFiles = true),
        )
        assertEquals(
            listOf("run", "black"),
            ByTaskCommands.arguments(node(ByTaskRunner.PRE_COMMIT, ByTaskKind.HOOK, "black"), allFiles = false),
        )
        assertEquals(
            listOf("run", "--all-files"),
            ByTaskCommands.arguments(node(ByTaskRunner.PRE_COMMIT, ByTaskKind.FILE), allFiles = true),
        )
    }

    /** The one flag the two spell differently, and the reason they are separate runners. */
    @Test
    fun `a stage is passed as hook-stage to pre-commit and as stage to prek`() {
        assertEquals(
            listOf("run", "ruff-format", "--hook-stage", "pre-push", "--all-files"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.PRE_COMMIT, ByTaskKind.HOOK, "ruff-format", stage = "pre-push"),
                allFiles = true,
            ),
        )
        assertEquals(
            listOf("run", "ruff-format", "--stage", "pre-push", "--all-files"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.PREK, ByTaskKind.HOOK, "ruff-format", stage = "pre-push"),
                allFiles = true,
            ),
        )
    }

    @Test
    fun `a pre-commit repo is a grouping and runs nothing`() {
        assertNull(
            ByTaskCommands.arguments(node(ByTaskRunner.PRE_COMMIT, ByTaskKind.SECTION, id = null), allFiles = true),
        )
    }

    @Test
    fun `lefthook runs a hook, one of its commands, or one of its jobs`() {
        assertEquals(
            listOf("run", "pre-commit", "--all-files", "--no-tty"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.LEFTHOOK, ByTaskKind.SECTION, "pre-commit", stage = "pre-commit"),
                allFiles = true,
            ),
        )
        assertEquals(
            listOf("run", "pre-commit", "--commands", "lint", "--no-tty"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.LEFTHOOK, ByTaskKind.COMMAND, "lint", stage = "pre-commit"),
                allFiles = false,
            ),
        )
        assertEquals(
            listOf("run", "pre-push", "--jobs", "types", "--no-tty"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.LEFTHOOK, ByTaskKind.JOB, "types", stage = "pre-push"),
                allFiles = false,
            ),
        )
    }

    /** No `--scripts` flag has ever existed, so the closest thing is the hook the script is in. */
    @Test
    fun `a lefthook script runs its whole hook`() {
        assertEquals(
            listOf("run", "pre-commit", "--no-tty"),
            ByTaskCommands.arguments(
                node(ByTaskRunner.LEFTHOOK, ByTaskKind.SCRIPT, id = null, stage = "pre-commit"),
                allFiles = false,
            ),
        )
    }

    @Test
    fun `lefthook has no command for a whole file`() {
        assertNull(ByTaskCommands.arguments(node(ByTaskRunner.LEFTHOOK, ByTaskKind.FILE), allFiles = true))
        // …nor for a command whose hook is unknown, which cannot be built into a valid line.
        assertNull(
            ByTaskCommands.arguments(
                node(ByTaskRunner.LEFTHOOK, ByTaskKind.COMMAND, "lint", stage = null),
                allFiles = false,
            ),
        )
    }

    @Test
    fun `pyprojectx takes the alias and nothing else`() {
        assertEquals(
            listOf("lint"),
            ByTaskCommands.arguments(node(ByTaskRunner.PYPROJECTX, ByTaskKind.ALIAS, "lint"), allFiles = true),
        )
        assertNull(ByTaskCommands.arguments(node(ByTaskRunner.PYPROJECTX, ByTaskKind.FILE), allFiles = true))
        assertFalse(ByTaskCommands.supportsAllFiles(ByTaskRunner.PYPROJECTX))
        assertTrue(ByTaskCommands.supportsAllFiles(ByTaskRunner.PRE_COMMIT))
    }

    @Test
    fun `the described command is what would be typed`() {
        assertEquals(
            "pre-commit run black --all-files",
            ByTaskCommands.describe("pre-commit", listOf("run", "black", "--all-files")),
        )
    }
}
