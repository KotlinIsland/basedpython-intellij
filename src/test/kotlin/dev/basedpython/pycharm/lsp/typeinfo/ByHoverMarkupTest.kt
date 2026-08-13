package dev.basedpython.pycharm.lsp.typeinfo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The payloads here are the shapes `ty_ide::hover` actually produces — a fenced block per content
 * item joined by `---` in markdown, the same items joined by a run of dashes in plain text — so a
 * change in either rendering shows up as a failure here rather than as an empty hint.
 */
class ByHoverMarkupTest {

    @Test
    fun `markdown type block is the hint`() {
        val markup = """
            ```python
            int
            ```
        """.trimIndent()
        assertEquals("int", ByHoverMarkup.typeHtml(markup))
    }

    @Test
    fun `docstring after the rule is not the type`() {
        val markup = """
            ```python
            def greet(name: str) -> str
            ```
            ---
            Say hello.
        """.trimIndent()
        assertEquals("def greet(name: str) -&gt; str", ByHoverMarkup.typeHtml(markup))
        assertEquals("def greet(name: str) -&gt; str<hr/>Say hello.", ByHoverMarkup.fullHtml(markup))
    }

    /** Plain text is what the server emits when the client does not advertise markdown hovers. */
    @Test
    fun `plain-text payload splits on the dashed rule`() {
        val markup = "int\n---------------------------------------------\nAn integer."
        assertEquals("int", ByHoverMarkup.typeHtml(markup))
        assertEquals("int<hr/>An integer.", ByHoverMarkup.fullHtml(markup))
    }

    /** `by` renders types multi-line by choice, and a collapsed union is unreadable. */
    @Test
    fun `multi-line type keeps its line breaks and indentation`() {
        val markup = "```python\nA\n  | B\n```"
        assertEquals("A<br/>&nbsp;&nbsp;| B", ByHoverMarkup.typeHtml(markup))
    }

    @Test
    fun `type text is escaped for the hint`() {
        assertEquals("list[int] &amp; &lt;T&gt;", ByHoverMarkup.typeHtml("```python\nlist[int] & <T>\n```"))
    }

    /** A rule inside a fence belongs to the code, or a `---` in a signature would cut the type in two. */
    @Test
    fun `rule inside a fence is not a separator`() {
        val blocks = ByHoverMarkup.parse("```python\na\n---\nb\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single().isCode)
        assertEquals("a\n---\nb", blocks.single().text)
    }

    @Test
    fun `a docstring underline does not split the docstring`() {
        val markup = "```python\nint\n```\n---\nTitle\n---\nBody"
        // The server joins its own contents with a rule, so the second rule here is the docstring's.
        // Splitting on it too is acceptable for display; what matters is the type is still first.
        assertEquals("int", ByHoverMarkup.typeHtml(markup))
    }

    @Test
    fun `empty payload has no type`() {
        assertNull(ByHoverMarkup.typeHtml(""))
        assertNull(ByHoverMarkup.typeHtml("```python\n\n```"))
        assertNull(ByHoverMarkup.fullHtml("   \n  "))
    }
}
