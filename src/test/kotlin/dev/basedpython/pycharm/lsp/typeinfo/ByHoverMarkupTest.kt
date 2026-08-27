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

    /**
     * A docstring is prose, so its `backticks` are markdown; the type block above it is python,
     * where a backtick is a character in a string and pairing it would be wrong.
     */
    @Test
    fun `a docstring's code spans are code, and the type block's backticks are not`() {
        val markup = "```python\ndef f() -> Literal[\"`\"]\n```\n---\nReturns a `backtick`."
        assertEquals("def f() -&gt; Literal[&quot;`&quot;]", ByHoverMarkup.typeHtml(markup))
        assertEquals(
            "def f() -&gt; Literal[&quot;`&quot;]<hr/>Returns a <code>backtick</code>.",
            ByHoverMarkup.fullHtml(markup),
        )
    }

    @Test
    fun `empty payload has no type`() {
        assertNull(ByHoverMarkup.typeHtml(""))
        assertNull(ByHoverMarkup.typeHtml("```python\n\n```"))
        assertNull(ByHoverMarkup.fullHtml("   \n  "))
    }

    // -------------------------------------------------------------------------
    // docstringMarkdown — the cut rendered documentation takes
    // -------------------------------------------------------------------------

    @Test
    fun `the docstring is what follows the type block and the rule`() {
        val markup = """
            ```python
            def greet(name: str) -> None
            ```
            ---
            Say hello.

            Longer prose.
        """.trimIndent()
        assertEquals("Say hello.\n\nLonger prose.", ByHoverMarkup.docstringMarkdown(markup))
    }

    /** The whole point of cutting rather than parsing: what `by` wrote reaches the converter intact. */
    @Test
    fun `the docstring keeps its own markdown, fences and lists and rules included`() {
        val markup = """
            ```python
            def run() -> None
            ```
            ---
            Runs it.

            * first
            * second

            ```python
            run()
            ```
        """.trimIndent()
        assertEquals(
            "Runs it.\n\n* first\n* second\n\n```python\nrun()\n```",
            ByHoverMarkup.docstringMarkdown(markup),
        )
    }

    @Test
    fun `a docstring's own underline stays in the docstring`() {
        val markup = "```python\nint\n```\n---\nTitle\n---\nBody"
        assertEquals("Title\n---\nBody", ByHoverMarkup.docstringMarkdown(markup))
    }

    @Test
    fun `a plain-text payload is cut at its dashed rule`() {
        val markup = "def greet(name: str) -> None\n----------------\nSay hello."
        assertEquals("Say hello.", ByHoverMarkup.docstringMarkdown(markup))
    }

    @Test
    fun `a longer fence is closed by a longer fence`() {
        val markup = "`````python\ndef f() -> None\n`````\n---\nDocs about ``` fences."
        assertEquals("Docs about ``` fences.", ByHoverMarkup.docstringMarkdown(markup))
    }

    /** A type with no docstring is not the same as an empty one: the caller falls back rather than blanking. */
    @Test
    fun `a payload with nothing after the type has no docstring`() {
        assertNull(ByHoverMarkup.docstringMarkdown("```python\nint\n```"))
        assertNull(ByHoverMarkup.docstringMarkdown("```python\nint\n```\n---\n"))
        assertNull(ByHoverMarkup.docstringMarkdown("```python\nint"))
        assertNull(ByHoverMarkup.docstringMarkdown(""))
    }

    /**
     * Captured from `by server` (ruff/0.16.2+452) hovering the name in `def greet(name: str) -> None:`
     * over a Google-style docstring. Two things here are the reason the text comes from the server
     * at all: `Args:` has become a `## Arguments` section, and the doctest has been fenced — with
     * eleven backticks, which is `render_markdown`'s way of making sure a docstring cannot break
     * out of its own fence. Neither survives a local markdown pass, and neither is reimplemented
     * here, so this payload is the contract.
     */
    @Test
    fun `a real Google-style docstring payload keeps its sections and its doctest fence`() {
        val markup = "```python\ndef greet(name: str)\n```\n---\nSay hello to *name*.\n\n" +
            "## Arguments\n**name**  \nwho to greet.\n\nExample:  \n" +
            "```````````python\n    >>> greet(\"world\")\n```````````"
        assertEquals(
            "Say hello to *name*.\n\n## Arguments\n**name**  \nwho to greet.\n\nExample:  \n" +
                "```````````python\n    >>> greet(\"world\")\n```````````",
            ByHoverMarkup.docstringMarkdown(markup),
        )
    }

    /** A class hover leads with an `xml` fence, not a `python` one — the cut is by fence, not by language. */
    @Test
    fun `a real class payload is cut the same way`() {
        val markup = "```xml\n<class 'Greeter'>\n```\n---\nGreets people.\n\n" +
            "## Parameters\n**loud**  \nwhether to shout."
        assertEquals(
            "Greets people.\n\n## Parameters\n**loud**  \nwhether to shout.",
            ByHoverMarkup.docstringMarkdown(markup),
        )
    }
}
