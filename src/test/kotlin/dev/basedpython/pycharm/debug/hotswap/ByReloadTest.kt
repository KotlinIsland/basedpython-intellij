package dev.basedpython.pycharm.debug.hotswap

import dev.basedpython.pycharm.lsp.ext.ByRestage
import dev.basedpython.pycharm.lsp.ext.ByRestageRefusal
import dev.basedpython.pycharm.lsp.ext.ByRestaged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What one press of the reload button writes into a running build's tree, driven against a real
 * directory.
 *
 * The defect these exist for: several `.by` files reloaded together each came back from `by` with a
 * map of their own — the tree's map plus that one file — and each was written whole in turn, so the
 * tree kept only the last file's line table. `by` now answers the set with one map; what is pinned
 * here is that the plugin writes that one map once, writes every module, and takes all of it back
 * together.
 */
class ByReloadTest {

    /** A tree as `by run` leaves it for two transpiled modules. */
    private fun tree(dir: Path): Path {
        Files.writeString(dir.resolve("a.py"), "def a():\n    return 1\n")
        Files.writeString(dir.resolve("b.py"), "def b():\n    return 1\n")
        Files.writeString(dir.resolve(ByReload.BY_SOURCEMAP), MAP_BEFORE)
        return dir
    }

    private fun slot(dir: Path, name: String, content: String, changed: Boolean = true) = ByRestaged(
        source = "/project/${name.removeSuffix(".py")}.by",
        generated = dir.resolve(name).toString(),
        content = content,
        byDigest = "sha256:by-$name",
        pyDigest = "sha256:py-$name",
        changed = changed,
    )

    /** Every file of the answer is written, and the map — one text for both — is written once. */
    @Test
    fun `an answer for two modules writes both and the one map describing both`(@TempDir dir: Path) {
        tree(dir)
        val answer = ByRestage(
            files = listOf(
                slot(dir, "a.py", "def a():\n    return 2\n"),
                slot(dir, "b.py", "def b():\n    return 2\n"),
            ),
            sourcemap = MAP_AFTER,
        )

        val plan = assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(answer))
        val written = ByBuildTree()
        ByReload.write(plan, dir, written)

        assertEquals("def a():\n    return 2\n", Files.readString(dir.resolve("a.py")))
        assertEquals("def b():\n    return 2\n", Files.readString(dir.resolve("b.py")))
        assertEquals(MAP_AFTER, Files.readString(dir.resolve(ByReload.BY_SOURCEMAP)))
        assertEquals(
            listOf(dir.resolve("a.py"), dir.resolve("b.py"), dir.resolve(ByReload.BY_SOURCEMAP)),
            written.written,
            "each module once, then the map once",
        )
        assertEquals(listOf(dir.resolve("a.py").toString(), dir.resolve("b.py").toString()), plan.replace)
    }

    /**
     * bpd refusing the replacement puts every file back — both modules and the map — exactly as
     * the process is still running them.
     */
    @Test
    fun `a refused replacement takes back every module and the map`(@TempDir dir: Path) {
        tree(dir)
        val answer = ByRestage(
            files = listOf(
                slot(dir, "a.py", "def a():\n    return 2\n"),
                slot(dir, "b.py", "def b():\n    return 2\n"),
            ),
            sourcemap = MAP_AFTER,
        )
        val plan = ByReload.plan(answer) as ByReload.Plan.Write
        val written = ByBuildTree()
        ByReload.write(plan, dir, written)

        assertEquals(emptyList<Path>(), written.rollback())

        assertEquals("def a():\n    return 1\n", Files.readString(dir.resolve("a.py")))
        assertEquals("def b():\n    return 1\n", Files.readString(dir.resolve("b.py")))
        assertEquals(MAP_BEFORE, Files.readString(dir.resolve(ByReload.BY_SOURCEMAP)))
    }

    /**
     * A write that fails part way — here the second module's slot is a directory — leaves what was
     * already written on disk; the rollback the provider then runs restores it, and the map was
     * never touched because it goes last.
     */
    @Test
    fun `a write that fails part way is taken back whole`(@TempDir dir: Path) {
        tree(dir)
        Files.delete(dir.resolve("b.py"))
        Files.createDirectory(dir.resolve("b.py"))
        val answer = ByRestage(
            files = listOf(
                slot(dir, "a.py", "def a():\n    return 2\n"),
                slot(dir, "b.py", "def b():\n    return 2\n"),
            ),
            sourcemap = MAP_AFTER,
        )
        val plan = ByReload.plan(answer) as ByReload.Plan.Write
        val written = ByBuildTree()

        assertThrows(IOException::class.java) { ByReload.write(plan, dir, written) }
        assertEquals(MAP_BEFORE, Files.readString(dir.resolve(ByReload.BY_SOURCEMAP)), "the map goes last")

        written.rollback()
        assertEquals("def a():\n    return 1\n", Files.readString(dir.resolve("a.py")))
    }

    /** A refusal of the set writes nothing, and names every file `by` gave a reason for. */
    @Test
    fun `a refused set writes nothing and names each file`() {
        val plan = ByReload.plan(
            ByRestage(
                refusals = listOf(
                    ByRestageRefusal(
                        file = "/project/b.by",
                        refused = "`/project/b.by` does not check, so it cannot be reloaded",
                        diagnostics = listOf("b.by:2:12: error[invalid-return-type] ..."),
                    ),
                    ByRestageRefusal(file = "/project/c.by", refused = "not one of the files"),
                ),
            ),
        )

        val refused = assertInstanceOf(ByReload.Plan.Refused::class.java, plan)
        assertEquals(2, refused.reasons.size)
        assertTrue(refused.reasons[0].startsWith("b.by: "), refused.reasons[0])
        assertTrue(refused.reasons[0].contains("\n  b.by:2:12: error[invalid-return-type]"), refused.reasons[0])
        assertTrue(refused.reasons[1].startsWith("c.by: "), refused.reasons[1])
    }

    /** A refusal about the tree, not a file, says nothing was reloaded rather than naming a file. */
    @Test
    fun `a refusal about the tree names no file`() {
        val plan = ByReload.plan(ByRestage(refusals = listOf(ByRestageRefusal(refused = "no _by_build.json"))))

        val refused = assertInstanceOf(ByReload.Plan.Refused::class.java, plan)
        assertEquals(listOf("nothing was reloaded: no _by_build.json"), refused.reasons)
    }

    /** Only the modules whose bytes moved are written and replaced; the map still carries them all. */
    @Test
    fun `an unchanged module is neither written nor replaced`(@TempDir dir: Path) {
        tree(dir)
        val answer = ByRestage(
            files = listOf(
                slot(dir, "a.py", "def a():\n    return 2\n"),
                slot(dir, "b.py", "def b():\n    return 1\n", changed = false),
            ),
            sourcemap = MAP_AFTER,
        )

        val plan = ByReload.plan(answer) as ByReload.Plan.Write
        val written = ByBuildTree()
        ByReload.write(plan, dir, written)

        assertEquals(listOf(dir.resolve("a.py").toString()), plan.replace)
        assertFalse(dir.resolve("b.py") in written.written)
        assertEquals(MAP_AFTER, Files.readString(dir.resolve(ByReload.BY_SOURCEMAP)))
    }

    /**
     * A `.by` edited so that it emits the same python still moves its line table and its digest, so
     * the map is written — and bpd is handed the set, because a remap only rides on a replacement.
     */
    @Test
    fun `a map that moved with no module that did is still written and remapped`(@TempDir dir: Path) {
        tree(dir)
        val answer = ByRestage(
            files = listOf(slot(dir, "a.py", "def a():\n    return 1\n", changed = false)),
            sourcemap = MAP_AFTER,
        )

        val plan = assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(answer))
        assertEquals(emptyList<ByRestaged>(), plan.files)
        assertEquals(listOf(dir.resolve("a.py").toString()), plan.replace)
        assertEquals(MAP_AFTER, plan.sourcemap)
    }

    /** Nothing moved at all: the process already matches the source. */
    @Test
    fun `an answer where nothing moved is up to date`(@TempDir dir: Path) {
        val answer = ByRestage(files = listOf(slot(dir, "a.py", "x", changed = false)), sourcemap = null)
        assertEquals(ByReload.Plan.UpToDate, ByReload.plan(answer))
    }

    /** A copied `.py` alone moves no map, so none is written. */
    @Test
    fun `a set that moves no map writes none`(@TempDir dir: Path) {
        tree(dir)
        val answer = ByRestage(files = listOf(slot(dir, "a.py", "def a():\n    return 2\n")), sourcemap = null)

        val plan = ByReload.plan(answer) as ByReload.Plan.Write
        val written = ByBuildTree()
        ByReload.write(plan, dir, written)

        assertEquals(listOf(dir.resolve("a.py")), written.written)
        assertEquals(MAP_BEFORE, Files.readString(dir.resolve(ByReload.BY_SOURCEMAP)))
    }

    private companion object {
        const val MAP_BEFORE = "SOURCEMAP = {\n    \"/t/a.py\": (\"/p/a.by\", [0]),\n    \"/t/b.py\": (\"/p/b.by\", [0]),\n}\n"
        const val MAP_AFTER = "SOURCEMAP = {\n    \"/t/a.py\": (\"/p/a.by\", [0, 1]),\n    \"/t/b.py\": (\"/p/b.by\", [0, 1]),\n}\n"
    }
}
