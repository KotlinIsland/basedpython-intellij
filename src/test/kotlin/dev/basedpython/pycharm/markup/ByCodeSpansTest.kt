package dev.basedpython.pycharm.markup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The messages here are the shapes `ty` actually emits — the type or symbol in backticks, the rest
 * prose — so the cases that matter are the ones where the backticks are not where a reader would
 * put them, which is the whole reason this is a parser and not a regex.
 */
class ByCodeSpansTest {

    @Test
    fun `a type in backticks becomes code`() {
        assertEquals(
            "Object of type <code>Literal[1]</code> is not callable",
            ByCodeSpans.toHtml("Object of type `Literal[1]` is not callable"),
        )
    }

    @Test
    fun `text with no backticks is only escaped`() {
        assertEquals("a &amp; b &lt;c&gt;", ByCodeSpans.toHtml("a & b <c>"))
    }

    /**
     * The reason the tooltip needed this at all: a tooltip is HTML, so a type the IDE reads as a
     * tag used to vanish into Swing's parser rather than show.
     */
    @Test
    fun `markup inside a span is escaped, not rendered`() {
        assertEquals(
            "Object of type <code>&lt;class &#39;int&#39;&gt;</code> is not callable",
            ByCodeSpans.toHtml("Object of type `<class 'int'>` is not callable"),
        )
    }

    @Test
    fun `a longer run delimits a span containing a backtick`() {
        assertEquals(
            """Type <code>Literal[&quot;`&quot;]</code> is wrong""",
            ByCodeSpans.toHtml("""Type ``Literal["`"]`` is wrong"""),
        )
    }

    /**
     * The unescaped case, which is what `ty` really emits: the closer that leaves `Literal["` with
     * an open quote is passed over for the one that closes it.
     */
    @Test
    fun `a string literal type carrying a backtick still pairs where it was meant to`() {
        assertEquals(
            """Type <code>Literal[&quot;`&quot;]</code> is not assignable to <code>str</code>""",
            ByCodeSpans.toHtml("""Type `Literal["`"]` is not assignable to `str`"""),
        )
    }

    /** The escape is `ty`'s own, so a literal quote inside a literal string must not fool it. */
    @Test
    fun `an escaped quote does not count as an open one`() {
        assertEquals(
            """Type <code>Literal[&quot;a\&quot;b&quot;]</code> is wrong""",
            ByCodeSpans.toHtml("""Type `Literal["a\"b"]` is wrong"""),
        )
    }

    /**
     * When nothing closes the quote, the message is about the quote — pairing then falls back to
     * the nearest run, which is what CommonMark would have done.
     */
    @Test
    fun `an unbalanced quote everywhere falls back to the nearest closer`() {
        assertEquals(
            """Expected <code>&quot;</code> but found <code>&#39;</code>""",
            ByCodeSpans.toHtml("""Expected `"` but found `'`"""),
        )
    }

    @Test
    fun `an unpaired backtick is text`() {
        assertEquals("a lone ` tick", ByCodeSpans.toHtml("a lone ` tick"))
        assertEquals("`str", ByCodeSpans.toHtml("`str"))
    }

    /** Bounding a span to its line is what keeps one stray backtick from spoiling the rest. */
    @Test
    fun `a span does not cross a line`() {
        assertEquals("open ` here<br/>and ` there", ByCodeSpans.toHtml("open ` here\nand ` there"))
    }

    @Test
    fun `line breaks and indentation survive`() {
        assertEquals("A<br/>&nbsp;&nbsp;| B", ByCodeSpans.toHtml("A\n  | B"))
    }

    /** Indentation is only indentation at the start of a line, not after a span on the same line. */
    @Test
    fun `spaces after a span are left as spaces`() {
        assertEquals("<code>x</code>  y", ByCodeSpans.toHtml("`x`  y"))
        assertEquals("&nbsp;&nbsp;<code>x</code> y", ByCodeSpans.toHtml("  `x` y"))
    }

    @Test
    fun `several spans on one line each become code`() {
        assertEquals(
            "<code>int</code> is not <code>str</code>",
            ByCodeSpans.toHtml("`int` is not `str`"),
        )
    }

    @Test
    fun `code marked as code is not searched for spans`() {
        assertEquals("x = `a`", ByCodeSpans.escapedHtml("x = `a`"))
        assertEquals("a<br/>&nbsp;&nbsp;b", ByCodeSpans.escapedHtml("a\n  b"))
    }

    @Test
    fun `empty text renders as nothing`() {
        assertEquals("", ByCodeSpans.toHtml(""))
        assertTrue(ByCodeSpans.spans("").isEmpty())
    }

    /** Whatever the pairing decides, the message keeps every character it arrived with. */
    @Test
    fun `no text is ever lost`() {
        val messages = listOf(
            "Object of type `Literal[1]` is not callable",
            """Type `Literal["`"]` is not assignable to `str`""",
            "a lone ` tick",
            "``x``",
            "`",
            "``",
            "` a\nb `",
            "no backticks at all",
        )
        for (message in messages) {
            val roundTrip = ByCodeSpans.spans(message).joinToString("") { span ->
                if (span.isCode) "`${span.text}`" else span.text
            }
            assertEquals(message.filterNot { it == '`' }, roundTrip.filterNot { it == '`' }, message)
        }
    }

    /** A span the parser found must not leak its delimiters into the rendered HTML. */
    @Test
    fun `a paired span leaves no backtick behind`() {
        val html = ByCodeSpans.toHtml("Argument to bound method `f` is incorrect: expected `int`")
        assertFalse(html.contains("`"), html)
    }
}
