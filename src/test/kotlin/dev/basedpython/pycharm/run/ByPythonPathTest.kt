package dev.basedpython.pycharm.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `PYTHONPATH` composition for the debugger's bootstrap directory.
 *
 * `by run` hands its environment straight to the interpreter, so this variable is shared between
 * the plugin and whatever the project set up for itself: overwriting it would break a project that
 * puts its own sources on the path, and a stray empty entry would silently add the current
 * directory to `sys.path`.
 */
class ByPythonPathTest {

    private val sep = File.pathSeparator

    @Test
    fun `the bootstrap directory goes in front of an inherited PYTHONPATH`() {
        assertEquals("/boot$sep/project/src", composePythonPath(listOf("/boot"), "/project/src"))
    }

    @Test
    fun `an absent or blank PYTHONPATH contributes nothing`() {
        assertEquals("/boot", composePythonPath(listOf("/boot"), null))
        assertEquals("/boot", composePythonPath(listOf("/boot"), ""))
        assertEquals("/boot", composePythonPath(listOf("/boot"), sep))
    }

    @Test
    fun `a directory already on the path is not duplicated`() {
        assertEquals("/boot", composePythonPath(listOf("/boot"), "/boot"))
        assertEquals("/boot$sep/a", composePythonPath(listOf("/boot"), "/a$sep/boot"))
    }

    @Test
    fun `no prefixes leaves the existing path alone`() {
        assertEquals("/a$sep/b", composePythonPath(emptyList(), "/a$sep/b"))
    }
}
