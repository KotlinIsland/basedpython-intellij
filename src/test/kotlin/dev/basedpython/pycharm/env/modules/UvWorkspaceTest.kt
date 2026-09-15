package dev.basedpython.pycharm.env.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A layout built from uv's member listing and the manifests on a real (temporary) disk.
 *
 * Which directories are members is uv's answer and is handed in here as a list, the way
 * `uv workspace list --paths` prints it; [UvModuleLiveTest] checks that answer against a real uv.
 * What is checked here is everything read *around* it: manifests, the root, `members` entries, and
 * who depends on whom. The manifests are the shortest ones uv would accept.
 */
class UvWorkspaceTest {

    @TempDir
    lateinit var root: Path

    private fun manifest(relative: String, text: String) {
        val directory = if (relative.isEmpty()) root else root.resolve(relative)
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("pyproject.toml"), text.trimIndent())
    }

    private fun member(name: String, dependencies: String = "[]") = """
        [project]
        name = "$name"
        version = "0.1.0"
        requires-python = ">=3.12"
        dependencies = $dependencies

        [build-system]
        requires = ["uv_build"]
        build-backend = "uv_build"
    """

    /** What uv printed: the listed directories, one absolute path each. */
    private fun listed(vararg relative: String): List<Path> =
        relative.map { if (it.isEmpty()) root else root.resolve(it) }

    @Test
    fun `a directory with no manifest is not a project`() {
        assertNull(UvWorkspace.read(root, emptyList()))
    }

    @Test
    fun `a single-package project is a layout with a root and no members`() {
        manifest("", member("solo"))
        val layout = checkNotNull(UvWorkspace.read(root, listed("")))
        assertEquals("solo", layout.root?.name)
        assertTrue(layout.members.isEmpty())
        assertFalse(layout.isWorkspace)
    }

    /**
     * Every directory uv lists is a member — including the two a hand-written walk used to prune,
     * and which uv's star under `packages` admits: `build` and a dot-directory.
     */
    @Test
    fun `every directory uv lists is a member, and the root is not one of them`() {
        manifest(
            "",
            """
            [project]
            name = "root"
            version = "0.1.0"

            [tool.uv.workspace]
            members = ["packages/*"]
            """,
        )
        manifest("packages/alpha", member("alpha"))
        manifest("packages/build", member("bld"))
        manifest("packages/.hidden", member("hid"))

        val layout = checkNotNull(
            UvWorkspace.read(root, listed("packages/alpha", "packages/build", "packages/.hidden", "")),
        )
        assertEquals("root", layout.root?.name)
        assertEquals(listOf("hid", "alpha", "bld"), layout.members.map { it.name })
        assertEquals(
            listOf("packages/.hidden", "packages/alpha", "packages/build"),
            layout.members.map { it.relativePath },
        )
        assertTrue(layout.isWorkspace)
    }

    /**
     * uv prints canonical paths; the IDE's project root need not be one. Modules must come back
     * under the root the IDE was given, or every relative path and every `members` entry is wrong.
     */
    @Test
    fun `a project opened through a symlink keeps its own spelling`(@TempDir elsewhere: Path) {
        manifest("", member("root"))
        manifest("packages/alpha", member("alpha"))
        val link = Files.createSymbolicLink(elsewhere.resolve("link"), root)

        val layout = checkNotNull(UvWorkspace.read(link, listOf(root.toRealPath().resolve("packages/alpha"))))
        val alpha = checkNotNull(layout.byName("alpha"))
        assertEquals(link.resolve("packages/alpha"), alpha.root)
        assertEquals("packages/alpha", alpha.relativePath)
    }

    /**
     * The distinction removal depends on: a module named outright can be un-listed, and one a glob
     * covers cannot be — see [ProjectModule.memberEntry].
     */
    @Test
    fun `a module knows whether it is listed by name or matched by a glob`() {
        manifest(
            "",
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["packages/*", "tools/lint"]
            """,
        )
        manifest("packages/alpha", member("alpha"))
        manifest("tools/lint", member("lint"))

        val layout = checkNotNull(UvWorkspace.read(root, listed("packages/alpha", "tools/lint", "")))
        assertNull(layout.byName("alpha")?.memberEntry)
        assertEquals("tools/lint", layout.byName("lint")?.memberEntry)
    }

    @Test
    fun `who depends on whom is read from the members' own manifests`() {
        manifest(
            "",
            """
            [project]
            name = "root"
            dependencies = ["alpha"]

            [tool.uv.workspace]
            members = ["packages/*"]
            """,
        )
        manifest("packages/alpha", member("alpha", """["beta"]"""))
        manifest("packages/beta", member("beta"))

        val layout = checkNotNull(UvWorkspace.read(root, listed("packages/alpha", "packages/beta", "")))
        assertEquals(listOf("alpha"), layout.dependents("beta").map { it.name })
        assertEquals(listOf("root"), layout.dependents("alpha").map { it.name })
        assertTrue(layout.dependents("root").isEmpty())
    }

    /** `my_lib` and `my-lib` are the same distribution, and a dependent naming either names it. */
    @Test
    fun `dependents are matched on the normalised name`() {
        manifest(
            "",
            """
            [project]
            name = "root"
            dependencies = ["my_lib"]

            [tool.uv.workspace]
            members = ["packages/*"]
            """,
        )
        manifest("packages/my-lib", member("my-lib"))

        val layout = checkNotNull(UvWorkspace.read(root, listed("packages/my-lib", "")))
        assertEquals(listOf("root"), layout.dependents("my-lib").map { it.name })
    }

    @Test
    fun `glob matching keeps a star inside one directory level`() {
        assertTrue(UvWorkspace.matches("packages/alpha", "packages/*"))
        assertFalse(UvWorkspace.matches("packages/alpha/nested", "packages/*"))
        assertTrue(UvWorkspace.matches("packages/alpha/nested", "packages/**"))
        assertTrue(UvWorkspace.matches("tools/lint", "tools/lint"))
        assertFalse(UvWorkspace.matches("tools/lint", "tools/format"))
    }

    @Test
    fun `a pattern naming one directory is the only kind that can be un-listed`() {
        assertTrue(UvWorkspace.isLiteral("packages/alpha"))
        assertFalse(UvWorkspace.isLiteral("packages/*"))
        assertFalse(UvWorkspace.isLiteral("**"))
    }
}
