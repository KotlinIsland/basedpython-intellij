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
 * Which directories are modules, read off a real (temporary) project.
 *
 * A temp directory rather than a mocked filesystem, because what is being checked is the part that
 * touches the disk: glob expansion, exclusion, and the pruning that keeps a scan from walking a
 * `.venv`. The manifests are the shortest ones uv would accept.
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

    @Test
    fun `a directory with no manifest is not a project`() {
        assertNull(UvWorkspace.read(root))
    }

    @Test
    fun `a single-package project is a layout with a root and no members`() {
        manifest("", member("solo"))
        val layout = checkNotNull(UvWorkspace.read(root))
        assertEquals("solo", layout.root?.name)
        assertTrue(layout.members.isEmpty())
        assertFalse(layout.isWorkspace)
    }

    @Test
    fun `a glob picks up every directory under it that has a manifest`() {
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
        manifest("packages/beta", member("beta"))
        // No manifest: a directory somebody made and has not initialised is not a member.
        Files.createDirectories(root.resolve("packages/gamma"))

        val layout = checkNotNull(UvWorkspace.read(root))
        assertEquals(listOf("alpha", "beta"), layout.members.map { it.name })
        assertEquals(listOf("packages/alpha", "packages/beta"), layout.members.map { it.relativePath })
        assertTrue(layout.isWorkspace)
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

        val layout = checkNotNull(UvWorkspace.read(root))
        assertNull(layout.byName("alpha")?.memberEntry)
        assertEquals("tools/lint", layout.byName("lint")?.memberEntry)
    }

    @Test
    fun `an excluded directory is not a member`() {
        manifest(
            "",
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["packages/*"]
            exclude = ["packages/scratch"]
            """,
        )
        manifest("packages/alpha", member("alpha"))
        manifest("packages/scratch", member("scratch"))

        val layout = checkNotNull(UvWorkspace.read(root))
        assertEquals(listOf("alpha"), layout.members.map { it.name })
    }

    /**
     * Every installed distribution that ships its own `pyproject.toml` sits under `.venv`, so a
     * recursive pattern that walked it would report a project's dependencies as its modules.
     */
    @Test
    fun `a recursive pattern does not descend into the environment`() {
        manifest(
            "",
            """
            [project]
            name = "root"

            [tool.uv.workspace]
            members = ["**"]
            """,
        )
        manifest("packages/alpha", member("alpha"))
        manifest(".venv/lib/python3.12/site-packages/httpx", member("httpx"))
        manifest("out/generated", member("generated"))

        val layout = checkNotNull(UvWorkspace.read(root))
        assertEquals(listOf("alpha"), layout.members.map { it.name })
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

        val layout = checkNotNull(UvWorkspace.read(root))
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

        val layout = checkNotNull(UvWorkspace.read(root))
        assertEquals(listOf("root"), layout.dependents("my-lib").map { it.name })
    }

    @Test
    fun `glob matching follows the same rules uv's does`() {
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
