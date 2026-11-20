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
        assertEquals(listOf("/project/a.by", "/project/b.by"), plan.replace, "bpd is handed what the user edited")
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

        assertEquals(listOf("/project/a.by"), plan.replace)
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
        assertEquals(listOf("/project/a.by"), plan.replace)
        assertEquals(MAP_AFTER, plan.sourcemap)
    }

    /**
     * A hand-written `.py` is copied into the tree, and the runner compiles the copy as the project
     * file: bpd is handed the project file, which is the only name the process knows that code by.
     * Handing it the copy was refused as code that had never run, on the first reload anyone tried.
     */
    @Test
    fun `a python file the build carried is replaced as the project file`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("main.py"), "def f():\n    a = 1\n")
        val answer = ByRestage(
            files = listOf(
                ByRestaged(
                    source = "/project/main.py",
                    generated = dir.resolve("main.py").toString(),
                    content = "def f():\n    a = 2\n",
                    changed = true,
                ),
            ),
            sourcemap = null,
        )

        val plan = assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(answer))
        ByReload.write(plan, dir, ByBuildTree())

        assertEquals("def f():\n    a = 2\n", Files.readString(dir.resolve("main.py")), "the copy is restaged")
        assertEquals(listOf("/project/main.py"), plan.replace)
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

    /**
     * `by` fcb4acae3 onward answers whether the map's line tables moved: a copied `.py` reloaded alone
     * still rewrites the map, to list the re-staged copy, and its tables are what they were. bpd is
     * told to read them again only when `by` says they moved — it used to be told whenever a map was
     * written, which read the copy's listing as a move.
     */
    @Test
    fun `bpd is told to remap when by says the tables moved, not whenever a map was written`(@TempDir dir: Path) {
        tree(dir)
        val listingOnly = ByRestage(files = listOf(slot(dir, "a.py", "def a():\n    return 2\n")), sourcemap = MAP_AFTER, remap = false)
        assertFalse(assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(listingOnly)).remap)

        val moved = ByRestage(files = listOf(slot(dir, "a.py", "def a():\n    return 2\n")), sourcemap = MAP_AFTER, remap = true)
        assertTrue(assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(moved)).remap)
    }

    /** A `by` from before the answer rewrote the map only when its tables moved, so a map written is a remap. */
    @Test
    fun `a by that does not say remaps whenever it rewrote the map`(@TempDir dir: Path) {
        tree(dir)
        val rewritten = ByRestage(files = listOf(slot(dir, "a.py", "def a():\n    return 2\n")), sourcemap = MAP_AFTER)
        assertTrue(assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(rewritten)).remap)
        val unchanged = ByRestage(files = listOf(slot(dir, "a.py", "def a():\n    return 2\n")), sourcemap = null)
        assertFalse(assertInstanceOf(ByReload.Plan.Write::class.java, ByReload.plan(unchanged)).remap)
    }

    /**
     * A program run in place: every edited file is handed bpd by its path, and `by`'s answer for each
     * `.by` — captured from `by retranspile` 8d9c9e1cf, which `by/retranspile` answers as it prints — is
     * passed on as `by` wrote it, every field kept, since bpd refuses one it does not know.
     */
    @Test
    fun `in place every edited file is handed over, with by's answers as it wrote them`() {
        val plan = ByReload.planInPlace(
            modules = listOf("/p/calc.by"),
            asIs = listOf("/p/helper.py"),
            answers = listOf(com.google.gson.JsonParser.parseString(RETRANSPILED).asJsonObject),
        )
        val replace = assertInstanceOf(ByReload.InPlace.Replace::class.java, plan)
        assertEquals(listOf("/p/calc.by", "/p/helper.py"), replace.files)
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(RETRANSPILED),
            replace.retranspiled.single(),
        )
        val arguments = ByReplaceCodeArguments(replace.files, remap = false, evenUnderALiveFrame = true, retranspiled = replace.retranspiled).toJson()
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(RETRANSPILED), (arguments["retranspiled"] as kotlinx.serialization.json.JsonArray).single())
    }

    /** A `.py` alone needs nothing of `by`, and a staged reload carries no `retranspiled` at all. */
    @Test
    fun `in place a py alone is handed over with no answers`() {
        val replace = assertInstanceOf(ByReload.InPlace.Replace::class.java, ByReload.planInPlace(emptyList(), listOf("/p/helper.py"), emptyList()))
        assertEquals(listOf("/p/helper.py"), replace.files)
        assertFalse(ByReplaceCodeArguments(replace.files, retranspiled = replace.retranspiled).toJson().containsKey("retranspiled"))
    }

    /** `by` refusing any module hands bpd nothing, and says why with the checker's sentences under it. */
    @Test
    fun `in place a refused module refuses the set, with its diagnostics`() {
        val refused = """{"source":"/p/calc.by","refused":"`/p/calc.by` does not check","diagnostics":["error[invalid-return-type]: Return type does not match"]}"""
        val plan = ByReload.planInPlace(
            modules = listOf("/p/calc.by", "/p/other.by"),
            asIs = emptyList(),
            answers = listOf(com.google.gson.JsonParser.parseString(refused).asJsonObject, com.google.gson.JsonParser.parseString(RETRANSPILED).asJsonObject),
        )
        assertEquals(
            ByReload.InPlace.Refused(listOf("calc.by: `/p/calc.by` does not check\n  error[invalid-return-type]: Return type does not match")),
            plan,
        )
    }

    @Test
    fun `in place an answer of the wrong length or shape hands over nothing`() {
        assertInstanceOf(ByReload.InPlace.Refused::class.java, ByReload.planInPlace(listOf("/p/calc.by"), emptyList(), emptyList()))
        assertInstanceOf(
            ByReload.InPlace.Refused::class.java,
            ByReload.planInPlace(listOf("/p/calc.by"), emptyList(), listOf(com.google.gson.JsonObject())),
        )
    }

    private companion object {
        const val RETRANSPILED = RETRANSPILED_ANSWER

        const val MAP_BEFORE = "SOURCEMAP = {\n    \"/t/a.py\": (\"/p/a.by\", [0]),\n    \"/t/b.py\": (\"/p/b.by\", [0]),\n}\n"
        const val MAP_AFTER = "SOURCEMAP = {\n    \"/t/a.py\": (\"/p/a.by\", [0, 1]),\n    \"/t/b.py\": (\"/p/b.by\", [0, 1]),\n}\n"
    }
}

/**
 * `by retranspile`'s answer for `app/calc.by` of a run in place, from `by` 8d9c9e1cf, with the cache
 * and project directories shortened to `/cache` and `/p`.
 */
private const val RETRANSPILED_ANSWER =
    """{"source":"/p/calc.by","key":"/cache/basedpython-run/fx-95c8ab702d19b60a/runs/d5f7/retranspiled/a4/b52e.py",""" +
        """"byPath":"/p/calc.by","lines":"0,1,2,3,4,5,6,7,8,9,10,11",""" +
        """"columns":"0 85 0 0;0 45 1 0;0 17 2 0,21 22 2 16,40 42 2 21;;;0 26 5 0;0 16 6 0;;;0 38 9 0;0 9 10 0,13 14 10 8,32 34 10 13;0 33 11 0;",""" +
        """"digests":{"by":"sha256:686dbb76092951be4a0ad944db7226de398ca0ba0b1bd04a0bc9d6ee8e9c8719","py":"sha256:87d1738b2e8844da65ccab4d0e0596859228b4a9da99a85874f40703bdd2a7fc"}}"""
