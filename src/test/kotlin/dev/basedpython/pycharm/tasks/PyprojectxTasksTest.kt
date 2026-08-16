package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What the `[tool.pyprojectx]` of a `pyproject.toml` turns into. */
class PyprojectxTasksTest {

    private val path = "pyproject.toml"

    private val config = """
        [project]
        name = "demo"

        [tool.pyprojectx]
        main = ["ruff", "pytest"]

        [tool.pyprojectx.aliases]
        lint = "ruff check src"
        test = ["pytest -q", "coverage report"]
        fmt = { cmd = "ruff format" }

        [tool.pyprojectx.aliases.docs]
        cmd = "mkdocs build"
        ctx = "main"
    """.trimIndent()

    private fun parse() = checkNotNull(PyprojectxTasks.parse(config, path))

    @Test
    fun `aliases become tasks, however they were written`() {
        val file = parse()

        assertEquals(path, file.name)
        assertEquals(ByTaskKind.FILE, file.kind)
        // Sorted: the aliases table has no meaningful order, unlike a list of hooks.
        assertEquals(listOf("docs", "fmt", "lint", "test"), file.children.map { it.name })
        assertEquals(listOf(ByTaskKind.ALIAS), file.children.map { it.kind }.distinct())
        assertEquals(listOf(ByTaskRunner.PYPROJECTX), file.children.map { it.runner }.distinct())
    }

    @Test
    fun `what an alias runs is shown beside it`() {
        val aliases = parse().children.associateBy { it.name }

        assertEquals("ruff check src", aliases.getValue("lint").detail)
        assertEquals("ruff format", aliases.getValue("fmt").detail)
        assertEquals("mkdocs build", aliases.getValue("docs").detail)
        // A list of commands is run in order, and reads as the shell line it behaves like.
        assertEquals("pytest -q && coverage report", aliases.getValue("test").detail)
    }

    @Test
    fun `an alias is selected by its own name and points at its line`() {
        val aliases = parse().children.associateBy { it.name }

        assertEquals("lint", aliases.getValue("lint").id)
        assertEquals(7, aliases.getValue("lint").line)
        assertEquals(11, aliases.getValue("docs").line)
    }

    @Test
    fun `tools are not tasks`() {
        // `main` defines a context of tools to install, not something to run.
        assertEquals(listOf("docs", "fmt", "lint", "test"), parse().children.map { it.name })
    }

    @Test
    fun `a pyproject that is not pyprojectx has nothing to offer`() {
        assertNull(PyprojectxTasks.parse("[project]\nname = \"demo\"\n", path))
        assertNull(PyprojectxTasks.parse("", path))
        // Configured, but with no aliases: nothing to list, so no file row either.
        assertNull(PyprojectxTasks.parse("[tool.pyprojectx]\nmain = [\"ruff\"]\n", path))
    }
}
