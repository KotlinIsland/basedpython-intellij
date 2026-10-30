package dev.basedpython.pycharm.transpile.explain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import dev.basedpython.pycharm.lsp.ext.ByTranspilationNote
import org.junit.jupiter.api.Test

/**
 * The explain report is HTML, and explanation text is written with markdown-style `code` spans.
 * Those must reach the user as real `<code>` elements — never as literal backticks — while still
 * being escaped against the source snippet injecting markup.
 */
class ExplainTranspilationRenderTest {

    private fun render(note: ByTranspilationNote) =
        ExplainTranspilationAction.renderHtml("main.by", listOf(note))

    private fun note(explanation: String, snippet: String = "a ?? b", line: Int = 1) =
        ByTranspilationNote(
            construct = "null-coalescing",
            snippet = snippet,
            explanation = explanation,
            line = line,
        )

    @Test
    fun `the report names the file and each construct's line`() {
        val html = render(note("plain text", line = 1234))
        assertTrue(html.contains("<b>Explain Transpilation: main.by</b>"), html)
        // A line number is not a quantity: 1234, never MessageFormat's 1,234.
        assertTrue(html.contains("<b>null-coalescing</b> (line 1234)<br/>"), html)
    }

    @Test
    fun `a file with nothing to explain says so`() {
        val html = ExplainTranspilationAction.renderHtml("main.by", emptyList())
        assertTrue(html.contains("<i>No basedpython-specific constructs were recognized.</i>"), html)
    }

    @Test
    fun `code spans in explanations render as code elements`() {
        val html = render(note("The `??` operator becomes `a if a is not None else b`."))
        assertTrue(html.contains("<code>??</code>"), html)
        assertTrue(html.contains("<code>a if a is not None else b</code>"), html)
    }

    @Test
    fun `no raw backtick survives into the report`() {
        val html = render(note("The `?.` access becomes `a.b if a is not None else None`."))
        assertFalse(html.contains("`"), "raw backtick leaked into user-visible HTML: $html")
    }

    @Test
    fun `markup in the source snippet is still escaped`() {
        // codeSpans runs after escape(), so it must not become an injection hole.
        val html = render(note("plain text", snippet = "<b>x</b>"))
        assertTrue(html.contains("&lt;b&gt;x&lt;/b&gt;"), html)
        assertFalse(html.contains("<b>x</b>"), html)
    }

    /**
     * Pairing is required, so a stray backtick can't swallow the remaining text. It does then reach
     * the user verbatim — acceptable only because no real explanation contains one, which the test
     * above enforces.
     */
    @Test
    fun `an unpaired backtick is left alone rather than eating the rest of the text`() {
        val html = render(note("a lone ` tick"))
        // The snippet always contributes one <code>; the explanation must contribute none.
        assertEquals(1, Regex("<code>").findAll(html).count())
        assertTrue(html.contains("a lone ` tick"), html)
    }
}
