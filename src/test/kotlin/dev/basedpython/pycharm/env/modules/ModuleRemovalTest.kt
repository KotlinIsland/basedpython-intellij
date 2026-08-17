package dev.basedpython.pycharm.env.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * What removing a module does to the project's own manifest.
 *
 * Three inputs, four outcomes, and getting one wrong is not a cosmetic failure: removing a glob
 * would unlist every *other* module it covers, and leaving an exact entry behind would leave the
 * project naming a directory that is not there.
 */
class ModuleRemovalTest {

    private fun module(relativePath: String, memberEntry: String?) = ProjectModule(
        name = relativePath.substringAfterLast('/'),
        root = Path.of("/project").resolve(relativePath),
        relativePath = relativePath,
        version = "0.1.0",
        description = null,
        requiresPython = null,
        dependencies = emptyList(),
        packaged = true,
        isRoot = false,
        memberEntry = memberEntry,
    )

    private val listedByName = """
        [project]
        name = "root"

        [tool.uv.workspace]
        members = [
            "packages/alpha",
            "packages/beta",
        ]
    """.trimIndent()

    private val listedByGlob = """
        [project]
        name = "root"

        [tool.uv.workspace]
        members = ["packages/*"]
    """.trimIndent()

    @Test
    fun `a module listed by name is taken out of the list, whatever happens to its files`() {
        val expected = """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = [
                "packages/beta",
            ]
        """.trimIndent()
        val alpha = module("packages/alpha", memberEntry = "packages/alpha")
        assertEquals(expected, ModuleOperations.unlisted(listedByName, alpha, deleteFiles = true))
        assertEquals(expected, ModuleOperations.unlisted(listedByName, alpha, deleteFiles = false))
    }

    /** The glob names its siblings too; the deleted directory simply stops matching it. */
    @Test
    fun `a module a glob covers needs no edit at all when its files go`() {
        val alpha = module("packages/alpha", memberEntry = null)
        assertEquals(listedByGlob, ModuleOperations.unlisted(listedByGlob, alpha, deleteFiles = true))
    }

    /** Keeping the files means the glob would still match it, so uv is told not to. */
    @Test
    fun `a module a glob covers is excluded when its files stay`() {
        val alpha = module("packages/alpha", memberEntry = null)
        assertEquals(
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["packages/*"]
            exclude = ["packages/alpha"]
            """.trimIndent(),
            ModuleOperations.unlisted(listedByGlob, alpha, deleteFiles = false),
        )
    }

    @Test
    fun `an exclusion joins one that is already there`() {
        val text = """
            [tool.uv.workspace]
            members = ["packages/*"]
            exclude = ["packages/old"]
        """.trimIndent()
        assertEquals(
            """
            [tool.uv.workspace]
            members = ["packages/*"]
            exclude = ["packages/old", "packages/alpha"]
            """.trimIndent(),
            ModuleOperations.unlisted(text, module("packages/alpha", memberEntry = null), deleteFiles = false),
        )
    }
}
