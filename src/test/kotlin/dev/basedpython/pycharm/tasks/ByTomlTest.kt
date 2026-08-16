package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The TOML subset `[tool.pyprojectx]` is read with. */
class ByTomlTest {

    private val pyproject = """
        [project]
        name = "demo"

        [tool.pyprojectx]
        main = [
          "ruff",
          "pytest",
        ]

        [tool.pyprojectx.aliases]
        lint = "ruff check src"
        test = ["pytest -q", "coverage report"]
        fmt = { cmd = "ruff format", ctx = "main" }

        [tool.pyprojectx.aliases.docs]
        cmd = "mkdocs build"
    """.trimIndent()

    @Test
    fun `tables are found by their dotted path`() {
        val sections = ByToml.parse(pyproject)

        assertTrue(ByToml.hasTable(sections, "tool", "pyprojectx"))
        assertTrue(ByToml.hasTable(sections, "project"))
        assertFalse(ByToml.hasTable(sections, "tool", "poetry"))
        assertEquals(
            listOf("lint", "test", "fmt"),
            ByToml.table(sections, "tool", "pyprojectx", "aliases").map { it.key },
        )
    }

    /** A `[tool.pyprojectx]` written nowhere but as a sub-table still makes it a pyprojectx project. */
    @Test
    fun `a table counts as present when only a table under it is written`() {
        val sections = ByToml.parse("[tool.pyprojectx.aliases]\nlint = \"ruff check\"\n")
        assertTrue(ByToml.hasTable(sections, "tool", "pyprojectx"))
    }

    @Test
    fun `a string value comes back unquoted, and a number does not come back at all`() {
        val sections = ByToml.parse(
            """
            [tool.pyprojectx.aliases]
            lint = "ruff check"
            literal = 'no \n escapes'
            count = 3
            """.trimIndent(),
        )
        val entries = ByToml.table(sections, "tool", "pyprojectx", "aliases").associateBy { it.key }

        assertEquals("ruff check", entries["lint"]?.string())
        assertEquals("no \\n escapes", entries["literal"]?.string())
        assertNull(entries["count"]?.string())
    }

    @Test
    fun `an array of strings is read, over as many lines as it takes`() {
        val entries = ByToml.table(ByToml.parse(pyproject), "tool", "pyprojectx").associateBy { it.key }

        assertEquals(listOf("ruff", "pytest"), entries["main"]?.strings())
    }

    @Test
    fun `an inline table gives up the key asked for`() {
        val entries = ByToml.table(ByToml.parse(pyproject), "tool", "pyprojectx", "aliases").associateBy { it.key }

        assertEquals("ruff format", entries["fmt"]?.inline("cmd"))
        assertEquals("main", entries["fmt"]?.inline("ctx"))
        assertNull(entries["fmt"]?.inline("cwd"))
        assertNull(entries["lint"]?.inline("cmd"))
    }

    @Test
    fun `a comment is dropped, and a hash inside a string is not`() {
        val entries = ByToml.table(
            ByToml.parse(
                """
                [tool.pyprojectx.aliases]
                # a whole-line comment
                tag = "git rev-parse HEAD" # trailing
                fragment = "curl http://x/#anchor"
                """.trimIndent(),
            ),
            "tool", "pyprojectx", "aliases",
        ).associateBy { it.key }

        assertEquals("git rev-parse HEAD", entries["tag"]?.string())
        assertEquals("curl http://x/#anchor", entries["fragment"]?.string())
    }

    @Test
    fun `a triple-quoted value keeps its lines`() {
        val entries = ByToml.table(
            ByToml.parse(
                """
                [tool.pyprojectx.aliases]
                release = ""${'"'}
                  git tag v1
                  git push --tags
                ""${'"'}
                """.trimIndent(),
            ),
            "tool", "pyprojectx", "aliases",
        ).associateBy { it.key }

        assertTrue(entries["release"]?.string()?.contains("git tag v1") == true)
        assertTrue(entries["release"]?.string()?.contains("git push --tags") == true)
    }

    @Test
    fun `entries carry the line they were written on`() {
        val entries = ByToml.table(ByToml.parse(pyproject), "tool", "pyprojectx", "aliases")

        assertEquals("lint", entries.first().key)
        assertEquals(10, entries.first().line)
    }
}
