package dev.basedpython.pycharm.debug.hotswap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of the files edited mid-session get sent to bpd, and which this plugin answers itself.
 */
class ByHotSwapTest {

    /**
     * The interpreter loads a `.py` from where the user wrote it — `by run` transpiles `.by` and
     * copies nothing else — so the file on disk *is* the file that is running, which is exactly the
     * comparison a replacement is.
     */
    @Test
    fun `a python file is the debugger's to replace`() {
        assertNull(ByHotSwap.refuse("/p/helper.py"))
        assertNull(ByHotSwap.refuse("/p/HELPER.PY"))
    }

    /**
     * The program is running the python `by run` transpiled the `.by` to, in a temp directory it
     * deletes on exit. Handing bpd the `.by` would be asking it to replace code no interpreter has
     * ever compiled.
     */
    @Test
    fun `a by file is refused, and the refusal says whose job the missing part is`() {
        val why = ByHotSwap.refuse("/p/main.by")
        assertNotNull(why)
        assertTrue(why!!.contains("transpiling it again"), why)
        assertTrue(why.contains("Restart"), why)
    }

    /** A file with no extension at all is not a `.by`, and bpd is the one that knows the rest. */
    @Test
    fun `a path with no extension is not treated as by`() {
        assertNull(ByHotSwap.refuse("/p/Makefile"))
    }

    @Test
    fun `a plan splits the change set and keeps a stable order`() {
        val plan = ByHotSwap.plan(listOf("/p/z.py", "/p/main.by", "/p/a.py"))
        assertEquals(listOf("/p/a.py", "/p/z.py"), plan.replaceable)
        assertEquals(listOf("/p/main.by"), plan.refused.map { it.first })
    }

    /**
     * A change set is a hash set, and the order it iterates in is not a fact about anything — a
     * console account of one session has to read the same way as the next.
     */
    @Test
    fun `the same change set plans the same way whatever order it arrives in`() {
        val one = ByHotSwap.plan(listOf("/p/b.py", "/p/a.py"))
        val other = ByHotSwap.plan(listOf("/p/a.py", "/p/b.py"))
        assertEquals(one, other)
    }

    @Test
    fun `a refused file is named by its own name, with why`() {
        val line = ByHotSwap.plan(listOf("/some/deep/path/main.by")).refusals().single()
        assertTrue(line.startsWith("did not reload main.by: "), line)
    }
}
