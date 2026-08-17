package dev.basedpython.pycharm.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * The debugger's bootstrap has to stay first. Prepending a directory also prepends its
     * `sitecustomize.py`, and `site` imports exactly one — a project that ships its own would
     * displace the bootstrap and the debugger would never start.
     */
    @Test
    fun `the working directory follows the bootstrap directory`() {
        assertEquals(
            "/boot$sep/project$sep/inherited",
            composePythonPath(listOf("/boot", "/project"), "/inherited"),
        )
    }

    /**
     * Only a run puts the project's own `.py` modules within reach, because only a run starts an
     * interpreter. A test run is a `by run pytest`, so it is the same case rather than a second one.
     */
    @Test
    fun `run starts a program and build and check do not`() {
        assertTrue(subcommandStartsProgram("run"))
        assertFalse(subcommandStartsProgram("build"))
        assertFalse(subcommandStartsProgram("check"))
    }
}
