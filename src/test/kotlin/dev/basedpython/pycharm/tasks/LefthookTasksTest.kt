package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a `lefthook.yml` turns into. */
class LefthookTasksTest {

    private val path = "lefthook.yml"

    private val config = """
        min_version: 1.5.0
        colors: false
        source_dir: .lefthook

        pre-commit:
          parallel: true
          commands:
            lint:
              glob: "*.py"
              run: ruff check {staged_files}
            format:
              run: |
                ruff format {staged_files}
                git add {staged_files}
          scripts:
            "good_job.sh":
              runner: bash

        pre-push:
          jobs:
            - name: test
              run: pytest -q
            - name: checks
              group:
                jobs:
                  - name: types
                    run: mypy .
    """.trimIndent()

    private fun parse() = checkNotNull(LefthookTasks.parse(config, path))

    @Test
    fun `only the keys that configure a hook become rows`() {
        val file = parse()

        assertEquals(listOf("pre-commit", "pre-push"), file.children.map { it.name })
        assertEquals(listOf(ByTaskKind.SECTION), file.children.map { it.kind }.distinct())
        // A stage is runnable, and is selected by its own name.
        assertEquals("pre-commit", file.children.first().id)
        assertEquals("pre-commit", file.children.first().stage)
    }

    @Test
    fun `commands and scripts of a hook are listed with what they run`() {
        val preCommit = parse().children.first()

        assertEquals(listOf("lint", "format", "good_job.sh"), preCommit.children.map { it.name })
        assertEquals(
            listOf(ByTaskKind.COMMAND, ByTaskKind.COMMAND, ByTaskKind.SCRIPT),
            preCommit.children.map { it.kind },
        )
        assertEquals("ruff check {staged_files}", preCommit.children[0].detail)
        // A block scalar is several lines; the row gets the first of them.
        assertEquals("ruff format {staged_files}", preCommit.children[1].detail)
        assertEquals("bash", preCommit.children[2].detail)
    }

    /** `lefthook run` can filter on commands and on jobs, never on scripts — so a script has no id. */
    @Test
    fun `a script carries its hook but nothing to select it by`() {
        val script = parse().children.first().children.single { it.kind == ByTaskKind.SCRIPT }

        assertNull(script.id)
        assertEquals("pre-commit", script.stage)
    }

    @Test
    fun `jobs are listed, and a group keeps the jobs inside it`() {
        val prePush = parse().children.last()

        assertEquals(listOf("test", "checks"), prePush.children.map { it.name })
        assertEquals(listOf(ByTaskKind.JOB), prePush.children.map { it.kind }.distinct())
        assertEquals("pytest -q", prePush.children[0].detail)

        val group = prePush.children[1]
        assertEquals(listOf("types"), group.children.map { it.name })
        assertEquals("mypy .", group.children.single().detail)
        // The nested job belongs to the same git hook as the group around it.
        assertEquals("pre-push", group.children.single().stage)
    }

    @Test
    fun `a task points at the line that declares it`() {
        val preCommit = parse().children.first()

        assertEquals(4, preCommit.line)
        assertEquals(7, preCommit.children.first().line)
    }

    /**
     * A group counts as what is inside it, not as itself plus what is inside it: `checks` and the
     * one job under it are one thing to run, and the count on a collapsed row answers "how much
     * does running this cost".
     */
    @Test
    fun `the count is of the work, not of the rows`() {
        // lint, format, good_job.sh | test, (checks →) types
        assertEquals(5, parse().taskCount)
    }

    @Test
    fun `a file that configures nothing runnable is nothing at all`() {
        assertNull(LefthookTasks.parse("", path))
        assertNull(LefthookTasks.parse("min_version: 1.5.0\ncolors: false\n", path))
        assertNull(LefthookTasks.parse("pre-commit:\n  parallel: true\n", path))
    }
}
