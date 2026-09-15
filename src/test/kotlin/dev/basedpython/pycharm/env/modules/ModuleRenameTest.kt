package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvOp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * A rename either happens or leaves the project exactly as it was.
 *
 * Driven against a real temporary workspace, with a stand-in for uv that edits manifests the way
 * `uv remove --package` and `uv add --package` do, and a stand-in for `by` that rewrites one import.
 * Every failure test takes a snapshot of every file in the project first and compares it afterwards:
 * "nothing changed" is checked, not assumed.
 *
 * The rename used to rewrite and save imports, then run `uv remove` in every sibling ignoring the
 * results, and only then try to move directories — so a move that failed returned with the imports
 * pointing at a module that was never renamed and the siblings no longer declaring it.
 */
class ModuleRenameTest {

    @TempDir
    lateinit var root: Path

    /** Fails the named step, the [failOn]th time it is reached, or throws [throwOn]'s exception. */
    private inner class FakeIo(
        private val failOn: String? = null,
        private val failAt: Int = 1,
        private val throwOn: String? = null,
        private val importsAnswer: Boolean = true,
    ) : ModuleRename.Io {
        val ran = mutableListOf<EnvOp>()
        val reports = mutableListOf<String>()
        private val reached = mutableMapOf<String, Int>()

        private fun fails(step: String): Boolean {
            if (step == throwOn) throw CancellationException("cancelled at $step")
            val count = reached.merge(step, 1, Int::plus)!!
            return step == failOn && count == failAt
        }

        override fun layout(): ModuleLayout? = UvWorkspace.read(
            root,
            Files.list(root.resolve("packages")).use { dirs -> dirs.toList() }.filter { Files.isDirectory(it) } + root,
        )

        override fun isDirectory(path: Path): Boolean = Files.isDirectory(path)

        override fun exists(path: Path): Boolean = Files.exists(path)

        override fun importEdits(moves: List<ModuleRenamePlan.Move>): ModuleRename.ImportEdits? {
            if (!importsAnswer) return null
            val importer = root.resolve("app.py")
            return object : ModuleRename.ImportEdits {
                private var before: String? = null
                override fun apply(): Boolean {
                    if (fails("imports")) return false
                    before = importer.readText()
                    Files.writeString(importer, before!!.replace("import alpha", "import omega"))
                    return true
                }

                override fun revert() {
                    before?.let { Files.writeString(importer, it) }
                }
            }
        }

        override fun move(move: ModuleRenamePlan.Move): Boolean {
            if (fails("move")) {
                reports += "move failed"
                return false
            }
            Files.move(move.from, move.to)
            return true
        }

        override fun read(file: Path): String? = if (file.isRegularFile()) file.readText() else null

        override fun write(file: Path, text: String?): Boolean {
            if (fails("write")) return false
            if (text == null) Files.deleteIfExists(file) else Files.writeString(file, text)
            return true
        }

        /** What uv does to a manifest, reduced to the one line these manifests declare a sibling on. */
        override fun run(op: EnvOp): Boolean {
            ran += op
            val step = when (op) {
                is EnvOp.Remove -> "remove"
                is EnvOp.Add -> "add"
                else -> error("unexpected $op")
            }
            // uv re-locks on every add and remove, whether or not it goes on to succeed.
            Files.writeString(root.resolve("uv.lock"), "locked after ${ran.size}\n")
            if (fails(step)) return false
            val module = checkNotNull(layout()?.byName(requireNotNull(op.moduleName)))
            val manifest = module.root.resolve(UvWorkspace.MANIFEST)
            val text = manifest.readText()
            Files.writeString(
                manifest,
                when (op) {
                    is EnvOp.Remove -> text.replace("\"${op.packages.single()}\"", "")
                    is EnvOp.Add -> text.replace("${op.target.key} = [", "${op.target.key} = [\"${op.requirements.single()}\"")
                    else -> text
                },
            )
            return true
        }

        override fun report(message: String) {
            reports += message
        }

        override fun progress(text: String) = Unit
    }

    private val EnvOp.moduleName: String?
        get() = when (this) {
            is EnvOp.Remove -> module
            is EnvOp.Add -> module
            else -> null
        }

    /** The TOML key a target's list lives under in these fixtures. */
    private val EnvDependencyTarget.key: String
        get() = when (this) {
            EnvDependencyTarget.Main -> "dependencies"
            is EnvDependencyTarget.Group -> name
            is EnvDependencyTarget.Extra -> name
        }

    private fun write(relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    /**
     * `alpha`, with `beta` using it at runtime and `gamma` using it only in its `dev` group, and an
     * importer at the root.
     */
    private fun workspace() {
        write(
            "pyproject.toml",
            """
            [project]
            name = "root"
            dependencies = []

            [tool.uv.workspace]
            members = ["packages/alpha", "packages/beta", "packages/gamma"]
            """,
        )
        write(
            "packages/alpha/pyproject.toml",
            """
            [project]
            name = "alpha"
            version = "0.1.0"
            dependencies = []
            """,
        )
        write("packages/alpha/src/alpha/__init__.py", "")
        write(
            "packages/beta/pyproject.toml",
            """
            [project]
            name = "beta"
            version = "0.1.0"
            dependencies = ["alpha"]
            """,
        )
        write(
            "packages/gamma/pyproject.toml",
            """
            [project]
            name = "gamma"
            version = "0.1.0"
            dependencies = []

            [dependency-groups]
            dev = ["alpha"]
            """,
        )
        write("app.py", "import alpha\n")
        write("uv.lock", "locked before\n")
    }

    /** Every file in the project and what it says. */
    private fun tree(): Map<String, String> =
        root.walk().filter { it.isRegularFile() }.associate { it.relativeTo(root).toString() to it.readText() }

    private fun rename(io: FakeIo): ProjectModule? {
        val alpha = checkNotNull(io.layout()?.byName("alpha"))
        return ModuleRename(io, root).rename(alpha, "omega")
    }

    @Test
    fun `a rename moves, renames and re-declares the module in the lists it was declared in`() {
        workspace()
        val io = FakeIo()

        val renamed = rename(io)

        assertNotNull(renamed)
        assertTrue(Files.isDirectory(root.resolve("packages/omega/src/omega")))
        assertFalse(Files.exists(root.resolve("packages/alpha")))
        assertTrue(root.resolve("packages/omega/pyproject.toml").readText().contains("name = \"omega\""))
        assertTrue(root.resolve("pyproject.toml").readText().contains("\"packages/omega\""))
        assertEquals("import omega\n", root.resolve("app.py").readText())

        val layout = checkNotNull(io.layout())
        assertEquals(listOf(EnvDependencyTarget.Main), layout.byName("beta")?.dependsOn("omega"))
        assertEquals(
            listOf(EnvDependencyTarget.Group("dev")),
            layout.byName("gamma")?.dependsOn("omega"),
            "a sibling that used it only in dev still uses it only in dev",
        )
    }

    @Test
    fun `a destination that already exists stops the rename before anything changes`() {
        workspace()
        write("packages/omega/README.md", "someone else's")
        val before = tree()
        val io = FakeIo()

        assertNull(rename(io))

        assertEquals(before, tree())
        assertTrue(io.ran.isEmpty(), "no uv command ran")
        assertEquals(1, io.reports.size)
    }

    @Test
    fun `a server that cannot say which imports to change stops the rename before anything changes`() {
        workspace()
        val before = tree()
        val io = FakeIo(importsAnswer = false)

        assertNull(rename(io))

        assertEquals(before, tree())
        assertTrue(io.ran.isEmpty())
    }

    @Test
    fun `a sibling that cannot be made to stop declaring it puts back the siblings that already had`() {
        workspace()
        val before = tree()
        val io = FakeIo(failOn = "remove", failAt = 2)

        assertNull(rename(io))

        assertEquals(before, tree(), "beta declares alpha again, and the lock is the one from before")
    }

    /** The failure the review found: the directory move failing after imports and siblings were edited. */
    @Test
    fun `a move that fails puts back the imports, the siblings and the directory moved before it`() {
        workspace()
        val before = tree()
        // The import package moves first; the module's own directory is the second move.
        val io = FakeIo(failOn = "move", failAt = 2)

        assertNull(rename(io))

        assertEquals(before, tree())
        assertTrue(Files.isDirectory(root.resolve("packages/alpha/src/alpha")))
    }

    @Test
    fun `a manifest that cannot be written puts everything back`() {
        workspace()
        val before = tree()
        val io = FakeIo(failOn = "write", failAt = 1)

        assertNull(rename(io))

        assertEquals(before, tree())
    }

    @Test
    fun `a sibling that cannot declare the new name puts everything back`() {
        workspace()
        val before = tree()
        val io = FakeIo(failOn = "add", failAt = 2)

        assertNull(rename(io))

        assertEquals(before, tree())
    }

    @Test
    fun `a lock file the rename created is removed again when it is rolled back`() {
        workspace()
        Files.delete(root.resolve("uv.lock"))
        val before = tree()
        val io = FakeIo(failOn = "add", failAt = 1)

        assertNull(rename(io))

        assertEquals(before, tree())
        assertFalse(Files.exists(root.resolve("uv.lock")))
    }

    @Test
    fun `a cancelled rename is put back and the cancellation goes on up`() {
        workspace()
        val before = tree()
        val io = FakeIo(throwOn = "add")

        assertThrows(CancellationException::class.java) { rename(io) }

        assertEquals(before, tree())
    }
}
