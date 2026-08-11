package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The contract between the IDE and the `sitecustomize.py` it injects: the report format, and the
 * environment the two sides have to agree on.
 */
class ByDebugBootstrapTest {

    private val bootstrap: String =
        checkNotNull(javaClass.getResourceAsStream("/debug/sitecustomize.py")) {
            "the debug bootstrap is missing from the plugin resources"
        }.use { it.readBytes().decodeToString() }

    /**
     * The variable names live in Kotlin *and* in Python, and nothing links them — a rename on one
     * side would leave a debugger that starts, waits, and times out with no explanation.
     */
    @Test
    fun `the bootstrap reads the environment the IDE writes`() {
        assertTrue(bootstrap.contains(ByDebugSetup.ENV_PORT), "bootstrap never mentions ${ByDebugSetup.ENV_PORT}")
        assertTrue(bootstrap.contains(ByDebugSetup.ENV_INFO_OUT), "bootstrap never mentions ${ByDebugSetup.ENV_INFO_OUT}")
    }

    /**
     * `by` runs the interpreter as `python -c ...` to probe its version. Activating there would try
     * to bind the debug port from a process that is not the program.
     */
    @Test
    fun `the bootstrap gates on the transpiled program`() {
        assertTrue(bootstrap.contains("_by_runner.py"), "bootstrap does not check what it was loaded into")
    }

    /**
     * On macOS the temp directory is reached through `/var` -> `/private/var`, `by` records the
     * unresolved form in `SOURCEMAP`, and Python reports the resolved one in frames. pydevd matches
     * `runtimeSource` against a frame's filename with no normalisation of its own, so without this
     * the breakpoint verifies and then never hits — observed live before the fix. `by run`'s own
     * `_by_runner.py` calls realpath for the same reason.
     */
    @Test
    fun `the bootstrap resolves generated paths before reporting them`() {
        assertTrue(
            bootstrap.contains("os.path.realpath(generated)"),
            "the generated path must be realpath'd or breakpoints verify but never hit",
        )
    }

    @Test
    fun `a listening report parses into mappable files`() {
        val info = ByDebuggeeInfo.parse(
            """
            {"status": "listening", "port": 5679, "python": "/usr/bin/python3",
             "runDir": "/tmp/x", "message": null,
             "files": [{"source": "/abs/demo.by", "generated": "/tmp/x/demo.py",
                        "lines": [null, 0, 1]}]}
            """.trimIndent()
        )
        assertNotNull(info)
        assertTrue(info!!.isListening)
        assertEquals(5679, info.port)
        assertEquals(listOf(null, 0, 1), info.mappedFiles.single().lines)
        assertEquals(
            listOf(ByLineRun(line = 1, endLine = 2, runtimeLine = 2)),
            ByLineMapping.invert(info.mappedFiles).single().runs,
        )
    }

    @Test
    fun `an error report is not mistaken for a live session`() {
        val info = ByDebuggeeInfo.parse("""{"status": "error", "message": "cannot import debugpy"}""")
        assertNotNull(info)
        assertFalse(info!!.isListening)
        assertEquals("cannot import debugpy", info.message)
        // Gson builds instances without running the constructor, so an absent key leaves null
        // behind whatever a Kotlin default declares. The error report carries no "files" at all.
        assertNull(info.files)
        assertTrue(info.mappedFiles.isEmpty())
    }

    /**
     * The failure this guards, taken from a real project: `main.by` beside an empty `src/main.by`,
     * both transpiled to one `main.py`. The empty one was written last, so it won — the program ran
     * and printed nothing, and no breakpoint could bind. `SOURCEMAP` cannot show this once loaded,
     * because it is a dict literal whose duplicate key has already collapsed.
     */
    @Test
    fun `a report names the sources that collided on one generated file`() {
        val info = ByDebuggeeInfo.parse(
            """
            {"status": "listening", "port": 1, "files": [],
             "collisions": [{"generated": "/tmp/x/main.py",
                             "sources": ["/p/main.by", "/p/src/main.by"]}]}
            """.trimIndent()
        )
        assertNotNull(info)
        val collision = info!!.realCollisions.single()
        assertEquals(listOf("/p/main.by", "/p/src/main.by"), collision.sources)
        // Write order, so the last is the one that survived and actually runs.
        assertEquals("/p/src/main.by", collision.sources!!.last())
    }

    /** One claim on a generated file is the normal case, not a collision. */
    @Test
    fun `a single source is not a collision`() {
        val info = ByDebuggeeInfo.parse(
            """{"status": "listening", "collisions": [{"generated": "/tmp/a.py", "sources": ["/p/a.by"]}]}"""
        )
        assertTrue(info!!.realCollisions.isEmpty())
    }

    /** Older bootstraps sent no `collisions` key at all. */
    @Test
    fun `a report without collisions reports none`() {
        val info = ByDebuggeeInfo.parse("""{"status": "listening", "files": []}""")
        assertTrue(info!!.realCollisions.isEmpty())
    }

    @Test
    fun `garbage is not a report`() {
        assertNull(ByDebuggeeInfo.parse("{ this is not json"))
    }

}
