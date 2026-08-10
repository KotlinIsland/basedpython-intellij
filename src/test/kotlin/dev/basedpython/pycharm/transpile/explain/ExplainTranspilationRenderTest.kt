package dev.basedpython.pycharm.transpile.explain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The explain report is HTML, and explanation text is written with markdown-style `code` spans.
 * Those must reach the user as real `<code>` elements — never as literal backticks — while still
 * being escaped against the source snippet injecting markup.
 */
class ExplainTranspilationRenderTest {

    private fun render(note: TranspilationNote) =
        ExplainTranspilationAction.renderHtml("main.by", listOf(note))

    private fun note(explanation: String, snippet: String = "a ?? b") =
        TranspilationNote(
            constructName = "null-coalescing",
            bySnippet = snippet,
            explanation = explanation,
            lineNumber = 1,
        )

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

    /** Every real explanation the explainer can emit must survive the same rule. */
    @Test
    fun `no explanation produced by the explainer leaks a backtick`() {
        val src = """
            a = b?.c
            d = e ?? f
            g = h!!
            i = j ?: k
        """.trimIndent()
        val notes = TranspilationExplainer.explain(src)
        assertTrue(notes.isNotEmpty(), "expected the explainer to recognize constructs")
        val html = ExplainTranspilationAction.renderHtml("main.by", notes)
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
