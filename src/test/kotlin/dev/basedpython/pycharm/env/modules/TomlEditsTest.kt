package dev.basedpython.pycharm.env.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The manifest edits the plugin makes itself, checked as text in and text out.
 *
 * These rewrite a file the user wrote, so what is *not* changed matters as much as what is: the
 * comments, the other tables, the array's shape, the line endings. Each of those is a case below,
 * and each of them was a way an earlier draft of this could have quietly reformatted somebody's
 * `pyproject.toml` on a version bump.
 */
class TomlEditsTest {

    private val project = listOf("project")
    private val workspace = listOf("tool", "uv", "workspace")

    @Test
    fun `setting a value replaces it and leaves the rest of the file alone`() {
        val text = """
            [project]
            name = "alpha"
            version = "0.1.0"

            [build-system]
            requires = ["uv_build"]
        """.trimIndent()
        assertEquals(
            """
            [project]
            name = "alpha"
            version = "2.0.0"

            [build-system]
            requires = ["uv_build"]
            """.trimIndent(),
            TomlEdits.setString(text, project, "version", "2.0.0"),
        )
    }

    @Test
    fun `a key the manifest does not have is added under the table`() {
        val text = """
            [project]
            name = "alpha"

            [build-system]
            requires = ["uv_build"]
        """.trimIndent()
        assertEquals(
            """
            [project]
            name = "alpha"
            requires-python = ">=3.12"

            [build-system]
            requires = ["uv_build"]
            """.trimIndent(),
            TomlEdits.setString(text, project, "requires-python", ">=3.12"),
        )
    }

    /** An empty description is the absence of one — see [TomlEdits.setString]. */
    @Test
    fun `clearing a value removes the key rather than writing an empty string`() {
        val text = """
            [project]
            name = "alpha"
            description = "does things"
            version = "0.1.0"
        """.trimIndent()
        assertEquals(
            """
            [project]
            name = "alpha"
            version = "0.1.0"
            """.trimIndent(),
            TomlEdits.setString(text, project, "description", ""),
        )
    }

    /**
     * The comment belongs to the key below it, which is where a person reading the file would put
     * it — deleting it along with the value above would be an edit nobody asked for.
     */
    @Test
    fun `a comment before the next key survives a replacement`() {
        val text = """
            [project]
            version = "0.1.0"
            # the name the wheel is published under
            name = "alpha"
        """.trimIndent()
        assertEquals(
            """
            [project]
            version = "0.2.0"
            # the name the wheel is published under
            name = "alpha"
            """.trimIndent(),
            TomlEdits.setString(text, project, "version", "0.2.0"),
        )
    }

    @Test
    fun `replacing a value that ran over several lines removes all of it`() {
        val text = """
            [project]
            authors = [
                { name = "A Person" }
            ]
            version = "0.1.0"
        """.trimIndent()
        assertEquals(
            """
            [project]
            authors = "someone"
            version = "0.1.0"
            """.trimIndent(),
            TomlEdits.setString(text, project, "authors", "someone"),
        )
    }

    @Test
    fun `a table the file does not have is left alone rather than invented`() {
        val text = "[tool.ruff]\nline-length = 100"
        assertEquals(text, TomlEdits.setString(text, project, "version", "1.0.0"))
    }

    @Test
    fun `a quote in a value is escaped rather than closing the string`() {
        val text = "[project]\ndescription = \"old\""
        assertEquals(
            "[project]\ndescription = \"a \\\"quoted\\\" thing\"",
            TomlEdits.setString(text, project, "description", "a \"quoted\" thing"),
        )
    }

    /** A manifest written on Windows must not come back as a single-line diff. */
    @Test
    fun `the file's own line endings are kept`() {
        val text = "[project]\r\nname = \"alpha\"\r\nversion = \"0.1.0\"\r\n"
        val updated = TomlEdits.setString(text, project, "version", "0.2.0")
        assertTrue(updated.contains("version = \"0.2.0\"\r\n"), updated)
        assertTrue(!updated.contains("\n\n"), "no line ending should have been rewritten: $updated")
    }

    // ---- members ------------------------------------------------------------

    @Test
    fun `removing a member keeps the array multi-line, as uv writes it`() {
        val text = """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = [
                "packages/alpha",
                "packages/beta",
            ]
        """.trimIndent()
        assertEquals(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = [
                "packages/beta",
            ]
            """.trimIndent(),
            TomlEdits.removeArrayItem(text, workspace, "members", "packages/alpha"),
        )
    }

    @Test
    fun `an array written on one line stays on one line`() {
        val text = "[tool.uv.workspace]\nmembers = [\"packages/alpha\", \"packages/beta\"]"
        assertEquals(
            "[tool.uv.workspace]\nmembers = [\"packages/beta\"]",
            TomlEdits.removeArrayItem(text, workspace, "members", "packages/alpha"),
        )
    }

    @Test
    fun `removing the only member leaves an empty list rather than a dangling bracket`() {
        val text = "[tool.uv.workspace]\nmembers = [\n    \"packages/alpha\",\n]"
        assertEquals(
            "[tool.uv.workspace]\nmembers = []",
            TomlEdits.removeArrayItem(text, workspace, "members", "packages/alpha"),
        )
    }

    /** How the entry is spelled is not how it is compared; a trailing slash names the same directory. */
    @Test
    fun `a member entry is matched however it was written`() {
        val text = "[tool.uv.workspace]\nmembers = [\"./packages/alpha/\", \"packages/beta\"]"
        assertEquals(
            "[tool.uv.workspace]\nmembers = [\"packages/beta\"]",
            TomlEdits.removeArrayItem(text, workspace, "members", "packages/alpha"),
        )
    }

    @Test
    fun `removing something that is not listed changes nothing`() {
        val text = "[tool.uv.workspace]\nmembers = [\"packages/alpha\"]"
        assertEquals(text, TomlEdits.removeArrayItem(text, workspace, "members", "packages/gamma"))
        assertEquals(text, TomlEdits.removeArrayItem(text, workspace, "exclude", "packages/alpha"))
    }

    // ---- exclude ------------------------------------------------------------

    @Test
    fun `excluding a directory creates the key when the table has none`() {
        val text = "[tool.uv.workspace]\nmembers = [\"packages/*\"]"
        assertEquals(
            "[tool.uv.workspace]\nmembers = [\"packages/*\"]\nexclude = [\"packages/old\"]",
            TomlEdits.addArrayItem(text, workspace, "exclude", "packages/old"),
        )
    }

    @Test
    fun `excluding the same directory twice adds one entry`() {
        val text = "[tool.uv.workspace]\nexclude = [\"packages/old\"]"
        assertEquals(text, TomlEdits.addArrayItem(text, workspace, "exclude", "packages/old"))
    }

    @Test
    fun `an added entry joins the array in the shape it already had`() {
        val text = "[tool.uv.workspace]\nexclude = [\n    \"packages/old\",\n]"
        assertEquals(
            "[tool.uv.workspace]\nexclude = [\n    \"packages/old\",\n    \"packages/older\",\n]",
            TomlEdits.addArrayItem(text, workspace, "exclude", "packages/older"),
        )
    }
}
