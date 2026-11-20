package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** bpd's sentences turned into the HTML the IDE's surfaces read. */
class ByBpdProseTest {

    /** The sentence that started this: a refused Reset Frame on a module frame read "runs ``". */
    @Test
    fun `a name in backticks is code, and a tag-shaped name inside it survives`() {
        assertEquals(
            "frame 1 runs <code>&lt;module&gt;</code> and cannot be run again",
            ByBpdProse.html("frame 1 runs `<module>` and cannot be run again"),
        )
    }

    @Test
    fun `text outside code is escaped, never read as markup`() {
        assertEquals("a &lt;b&gt; c &amp; d", ByBpdProse.html("a <b> c & d"))
    }

    @Test
    fun `emphasis is bold, and two emphasised phrases stay two`() {
        assertEquals(
            "<b>the cleanup did not run</b> and <b>whole</b> line",
            ByBpdProse.html("**the cleanup did not run** and **whole** line"),
        )
    }

    @Test
    fun `asterisks inside code are code, not emphasis`() {
        assertEquals("<code>**kwargs</code> and <code>a ** b</code>", ByBpdProse.html("`**kwargs` and `a ** b`"))
    }

    /** CommonMark's double-backtick span, the one way to put a backtick inside code. */
    @Test
    fun `a double-backtick span holds a backtick`() {
        assertEquals("<code>a ` b</code>", ByBpdProse.html("``a ` b``"))
    }

    @Test
    fun `an unpaired backtick is left as a character`() {
        assertEquals("a `half", ByBpdProse.html("a `half"))
    }

    @Test
    fun `a line break is kept`() {
        assertEquals("one<br>two", ByBpdProse.html("one\ntwo"))
    }
}
