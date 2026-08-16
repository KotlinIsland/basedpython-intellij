package dev.basedpython.pycharm.tasks

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What double-clicking a row produces: a run configuration that names the same command the row
 * showed, and reports its verdict back to the same key the row is drawn from.
 *
 * Nothing here starts a process — [ByTaskActions.configure] builds the configuration that [run]
 * would then execute, which is where everything worth asserting has already happened.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTaskConfigurationTest {

    private val fixture by codeInsightFixture()

    private val hook = ByTaskNode(
        name = "black",
        kind = ByTaskKind.HOOK,
        runner = ByTaskRunner.PRE_COMMIT,
        path = ".pre-commit-config.yaml",
        id = "black",
        stage = "pre-push",
    )

    private fun configure(node: ByTaskNode): ByTaskConfiguration? =
        ByTaskActions.configure(fixture.project, node)?.configuration as? ByTaskConfiguration

    @Test
    fun `a task becomes a configuration that rebuilds its own command`() {
        val configuration = checkNotNull(configure(hook))

        assertEquals("pre-commit: black", configuration.name)
        assertEquals(
            listOf("run", "black", "--hook-stage", "pre-push", "--all-files"),
            configuration.arguments(),
        )
    }

    /** The five persisted fields are what the tree keys verdicts by, so they have to agree exactly. */
    @Test
    fun `the configuration reports to the row that started it`() {
        val configuration = checkNotNull(configure(hook))

        assertEquals(hook.key, configuration.taskKey())
    }

    @Test
    fun `the all-files preference of the project is the one that is used`() {
        val service = ByTaskService.getInstance(fixture.project)
        try {
            service.allFiles = false
            assertEquals(listOf("run", "black", "--hook-stage", "pre-push"), configure(hook)?.arguments())
            service.allFiles = true
            assertEquals(
                listOf("run", "black", "--hook-stage", "pre-push", "--all-files"),
                configure(hook)?.arguments(),
            )
        } finally {
            service.allFiles = true
        }
    }

    /** pyprojectx has no file list, so the preference does not reach its command line. */
    @Test
    fun `an alias never grows an all-files flag`() {
        val alias = ByTaskNode(
            name = "lint",
            kind = ByTaskKind.ALIAS,
            runner = ByTaskRunner.PYPROJECTX,
            path = "pyproject.toml",
            id = "lint",
        )

        assertEquals(listOf("lint"), configure(alias)?.arguments())
    }

    @Test
    fun `a grouping row produces no configuration at all`() {
        val repo = ByTaskNode(
            name = "psf/black",
            kind = ByTaskKind.SECTION,
            runner = ByTaskRunner.PRE_COMMIT,
            path = ".pre-commit-config.yaml",
        )

        assertNull(ByTaskActions.configure(fixture.project, repo))
    }

    /** A configuration loaded from a file written by a newer plugin must degrade, not fail. */
    @Test
    fun `an unknown runner or kind falls back rather than throwing`() {
        val configuration = checkNotNull(configure(hook))
        configuration.options.runner = "something-else"
        configuration.options.taskKind = "SOMETHING_ELSE"

        assertEquals(ByTaskRunner.PRE_COMMIT, ByTaskRunner.fromId(configuration.options.runner))
        // Unknown kind reads as the file, which for pre-commit is "run everything".
        assertEquals(listOf("run", "--all-files"), configuration.arguments())
    }
}
