package dev.basedpython.pycharm.docs.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The text path, taken only by a module docstring — the one `by` cannot be asked about.
 *
 * What these mostly assert is what is *not* done. Reading a raw docstring as markdown was the bug
 * this replaced: a doctest came out as nested blockquotes with its `>>>` eaten, because that is
 * what `>>>` means to a markdown parser.
 */
class ByDocstringTextTest {

    private val q = "\"\"\""

    @Test
    fun `a doctest stays a doctest`() {
        val html = ByDocstringText.html("${q}Convert a number.\n\n>>> int('0b100', base=0)\n4\n$q")!!
        assertTrue(html.contains("&gt;&gt;&gt; int(&#39;0b100&#39;, base=0)"), html)
        assertTrue("blockquote" !in html, "read as markdown, not as text: $html")
    }

    /** Indentation past what every line shares is the docstring's own layout, and has to survive. */
    @Test
    fun `indentation kept after the trim is spelled out, not collapsed or read as code`() {
        val html = ByDocstringText.html("${q}Summary.\n\n    base line\n        deeper line\n$q")!!
        assertTrue(html.contains("&nbsp;&nbsp;&nbsp;&nbsp;deeper line"), html)
        assertTrue("<pre" !in html && "<code" !in html, "read as a code block: $html")
    }

    @Test
    fun `blank lines separate paragraphs and line breaks inside one are kept`() {
        val html = ByDocstringText.html("${q}First.\nsame paragraph.\n\nSecond.\n$q")
        assertEquals("<p>First.<br/>same paragraph.</p><p>Second.</p>", html)
    }

    @Test
    fun `html in a docstring is shown, not interpreted`() {
        val html = ByDocstringText.html("${q}Returns a <list> of x & y.$q")!!
        assertTrue(html.contains("&lt;list&gt; of x &amp; y"), html)
    }

    // -------------------------------------------------------------------------
    // PEP 257 trimming
    // -------------------------------------------------------------------------

    /**
     * The first line begins after the quotes at column zero, the rest are indented by the code
     * around them; the PEP's rule is that only the rest share an indent to remove.
     */
    @Test
    fun `the shared indentation of the later lines goes, the first line stands alone`() {
        assertEquals(
            "Summary.\n\nDetail.\n    Deeper.",
            ByDocstringText.trimIndentation("Summary.\n\n    Detail.\n        Deeper.\n    "),
        )
    }

    @Test
    fun `a docstring that starts on the line below its quotes loses its empty first line`() {
        assertEquals("Summary.\nMore.", ByDocstringText.trimIndentation("\n    Summary.\n    More.\n    "))
    }

    // -------------------------------------------------------------------------
    // the literal
    // -------------------------------------------------------------------------

    @Test
    fun `quotes and prefixes come off`() {
        assertEquals("Docs.", ByDocstringText.body("${q}Docs.$q"))
        assertEquals("Docs.", ByDocstringText.body("'''Docs.'''"))
        assertEquals("Docs.", ByDocstringText.body("\"Docs.\""))
        assertEquals("Docs.", ByDocstringText.body("r${q}Docs.$q"))
        assertEquals("", ByDocstringText.body("$q$q"))
    }

    @Test
    fun `an unterminated literal keeps what follows the quotes`() {
        assertEquals("half written", ByDocstringText.body("${q}half written"))
    }

    @Test
    fun `an empty docstring renders nothing at all`() {
        assertNull(ByDocstringText.html("$q$q"))
        assertNull(ByDocstringText.html("$q   \n  \n$q"))
    }
}
