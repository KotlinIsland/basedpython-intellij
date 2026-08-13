package dev.basedpython.pycharm.env

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Making a file on disk executable.
 *
 * Both ways a binary can arrive without its execute bit funnel through here: the download action
 * writes one with [com.intellij.util.io.HttpRequests] (which has no notion of file modes), and the
 * bundled binaries arrive inside the plugin zip, which the IDE's installer unpacks without
 * restoring unix modes. In both cases the file is otherwise perfectly good and one `chmod` away
 * from being runnable, so neither caller should give up on it.
 */
object Executables {

    private val LOG = Logger.getInstance(Executables::class.java)

    /**
     * Adds the execute bits to [path], returning whether it is executable afterwards.
     *
     * A filesystem with no POSIX permission view — Windows, chiefly — reports success without
     * touching anything: there is no execute bit to add there, and the file is already runnable.
     */
    fun makeExecutable(path: Path): Boolean {
        try {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms.add(PosixFilePermission.OWNER_EXECUTE)
            perms.add(PosixFilePermission.GROUP_EXECUTE)
            perms.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // No POSIX view (Windows). Nothing to add, and nothing was ever missing.
            return true
        } catch (ex: Exception) {
            LOG.warn("Could not mark $path executable", ex)
            return false
        }
        return Files.isExecutable(path)
    }
}
