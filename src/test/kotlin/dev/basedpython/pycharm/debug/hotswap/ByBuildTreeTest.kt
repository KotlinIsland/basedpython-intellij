package dev.basedpython.pycharm.debug.hotswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Putting a build tree back after a refused replacement.
 *
 * This is the part of hot reload most able to be quietly wrong: it only ever runs on the failure
 * path, so a rollback that misses a file leaves a debug session reading lines out of a tree nobody
 * built — and it would be a state the *plugin* created, on a file the user only edited in the
 * editor.
 */
class ByBuildTreeTest {

    @Test
    fun `a file that was there comes back exactly as it was`(@TempDir dir: Path) {
        val file = dir.resolve("main.py")
        Files.writeString(file, "def f():\n    return 1\n")

        val tree = ByBuildTree()
        tree.write(file, "def f():\n    return 2\n")
        assertEquals("def f():\n    return 2\n", Files.readString(file))

        assertEquals(emptyList<Path>(), tree.rollback())
        assertEquals("def f():\n    return 1\n", Files.readString(file))
    }

    /**
     * Restoring a file that did not exist means deleting it, not writing an empty one — an empty
     * `.py` still imports, and would be a module the program could reach that no build ever wrote.
     */
    @Test
    fun `a file that was not there is deleted again`(@TempDir dir: Path) {
        val file = dir.resolve("new.py")

        val tree = ByBuildTree()
        tree.write(file, "x = 1\n")
        assertTrue(Files.exists(file))

        tree.rollback()
        assertFalse(Files.exists(file))
    }

    /**
     * The unit is the whole set: one edit can change the python emitted for several modules, and
     * `_by_sourcemap.py` is rewritten beside them. A rollback that put some of them back would leave
     * a third state that was never anything.
     */
    @Test
    fun `every file of a set goes back together`(@TempDir dir: Path) {
        val one = dir.resolve("a.py").also { Files.writeString(it, "a = 1\n") }
        val two = dir.resolve("b.py").also { Files.writeString(it, "b = 1\n") }
        val map = dir.resolve("_by_sourcemap.py").also { Files.writeString(it, "SOURCEMAP = {}\n") }

        val tree = ByBuildTree()
        tree.write(one, "a = 2\n")
        tree.write(two, "b = 2\n")
        tree.write(map, "SOURCEMAP = {'a': 1}\n")

        tree.rollback()

        assertEquals("a = 1\n", Files.readString(one))
        assertEquals("b = 1\n", Files.readString(two))
        assertEquals("SOURCEMAP = {}\n", Files.readString(map))
    }

    /**
     * The state worth going back to is the one the program started from, not the one the last write
     * replaced — a second write to a path is a later version of the same edit.
     */
    @Test
    fun `two writes to one path still go back to what the program started with`(@TempDir dir: Path) {
        val file = dir.resolve("main.py")
        Files.writeString(file, "original\n")

        val tree = ByBuildTree()
        tree.write(file, "first\n")
        tree.write(file, "second\n")
        tree.rollback()

        assertEquals("original\n", Files.readString(file))
    }

    /**
     * A directory the build did not have yet is made on the way, because the transpiler can emit a
     * module into a package the previous stage had no file in.
     */
    @Test
    fun `a file lands in a directory that did not exist`(@TempDir dir: Path) {
        val file = dir.resolve("pkg/deep/mod.py")

        val tree = ByBuildTree()
        tree.write(file, "x = 1\n")

        assertEquals("x = 1\n", Files.readString(file))
        tree.rollback()
        assertFalse(Files.exists(file))
    }

    /**
     * Nothing was written, so nothing is stranded — and rollback on the path where the very first
     * write threw must not itself fail.
     */
    @Test
    fun `rolling back nothing is not a failure`() {
        assertEquals(emptyList<Path>(), ByBuildTree().rollback())
    }
}
