package dev.basedpython.pycharm.debug.hotswap

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writing re-staged files into the tree a debuggee is running out of, and taking it all back.
 *
 * ## why anything is taken back
 *
 * A replacement can be refused after the bytes are on disk — bpd decides that by compiling the file
 * and walking it against the code the process is running, which it cannot do until the file is
 * there. A tree left holding code the process is not running is a tree that **lies**: `bpd` reads
 * `_by_sourcemap.py` out of it to say which `.by` line a frame is on, and reads the generated python
 * to prove a frame's code object is still in it. Both answers would then be about a file that no
 * longer exists anywhere except on disk.
 *
 * bpd is honest about it — a frame whose code is not in what compiles comes back `not_the_same_code`
 * rather than a line — so the failure is loud rather than silent. It is still a session degraded by
 * a write the *plugin* chose to make, on a file the user only edited in the editor. So every write
 * is remembered and undone together.
 *
 * ## all of them, or none
 *
 * The unit is the whole set. One `.by` edit can change the python emitted for more than one module,
 * and `_by_sourcemap.py` is rewritten beside them; a rollback that put some files back would leave
 * the tree in a third state that was never anything. [rollback] therefore restores every file it
 * recorded, including the ones whose own write succeeded.
 *
 * Not thread-safe, and does not need to be: one press of the button owns one of these from the first
 * write to the last.
 */
internal class ByBuildTree {

    /**
     * What each path held before this wrote to it, in the order they were written.
     *
     * A `null` value is a path that did not exist — restoring it means deleting it again, not
     * writing an empty file. Nothing in a `by run` tree is expected to be new, but a `by` that
     * starts emitting a module the previous stage did not would otherwise leave a file behind that
     * no build wrote.
     */
    private val before = LinkedHashMap<Path, ByteArray?>()

    /** Every path this has written to, for naming what was touched. */
    val written: List<Path> get() = before.keys.toList()

    /**
     * Put `content` at `path`, remembering what was there.
     *
     * Recorded **before** the write and only once per path: a second write to one path is a later
     * version of the same edit, and the state worth going back to is the one the program started
     * with.
     */
    @Throws(IOException::class)
    fun write(path: Path, content: String) {
        if (!before.containsKey(path)) {
            before[path] = if (Files.exists(path)) Files.readAllBytes(path) else null
        }
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, content.toByteArray())
    }

    /**
     * Put every file back the way it was.
     *
     * Best effort by design, and it reports rather than throws: this runs on the failure path, and a
     * rollback that threw would replace a refusal the user can act on with an exception they cannot.
     * What it returns is the paths it could **not** restore — an empty list is a tree that is once
     * again exactly what the process is running.
     */
    fun rollback(): List<Path> {
        val failed = mutableListOf<Path>()
        for ((path, contents) in before) {
            try {
                if (contents == null) Files.deleteIfExists(path) else Files.write(path, contents)
            } catch (e: IOException) {
                LOG.warn("could not put $path back after a refused replacement", e)
                failed.add(path)
            }
        }
        before.clear()
        return failed
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ByBuildTree::class.java)
    }
}
