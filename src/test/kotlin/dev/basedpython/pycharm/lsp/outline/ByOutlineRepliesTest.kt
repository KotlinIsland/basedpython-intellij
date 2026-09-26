package dev.basedpython.pycharm.lsp.outline

import com.google.gson.Gson
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineResponse
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading a real `by/syntaxOutline` reply.
 *
 * `outline/golden.json` is what `by` answered for `outline/golden.by`, captured from the server
 * over stdio rather than written by hand, so this is the contract: field names, the fields `by`
 * leaves out when they are empty, and UTF-16 positions past a non-ASCII character. [OutlineSpec]
 * writes the same shapes for the other tests, and this is what keeps it honest.
 */
@TestFixtures
class ByOutlineRepliesTest {

    private val fixture by codeInsightFixture()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/outline/$name")) { name }.reader().readText()

    private fun read(): Pair<String, ByOutline?> {
        val source = resource("golden.by")
        fixture.configureByText("golden.by", source)
        val response = Gson().fromJson(resource("golden.json"), BySyntaxOutlineResponse::class.java)
        return source to ByOutlineReplies.read(response, fixture.editor.document)
    }

    @Test
    fun `the reply reads into the statements, clauses and strings of the document`() = onEdt {
        val (text, outline) = read()
        checkNotNull(outline)
        fun TextRange.text() = substring(text)

        // `match: int = 1` is an annotated assignment: a simple statement, with no clauses.
        assertEquals(2, outline.statements.size)
        assertEquals("match: int = 1", outline.statements[0].range.text())
        assertEquals(emptyList<ByOutline.Clause>(), outline.statements[0].clauses)
        val def = outline.statements[1].clauses.single()
        assertEquals("def", def.keyword?.text())
        assertEquals(":", def.colon?.text())

        val (docstring, ifStatement) = def.body
        assertEquals(emptyList<ByOutline.Clause>(), docstring.clauses)
        assertEquals(listOf("if", "elif", "else"), ifStatement.clauses.map { it.keyword?.text() })
        assertEquals("if x:", TextRange(ifStatement.clauses[0].range.startOffset, ifStatement.clauses[0].colon!!.endOffset).text())

        val print = ifStatement.clauses[0].body.single().call!!
        assertEquals("print", print.callee.text())
        assertEquals("f\"x\\n{x!r}\"", print.arguments?.text())
        assertEquals(true, print.positionalOnly)

        val strings = outline.strings
        assertEquals(4, strings[0].strippedIndent)
        assertEquals(listOf("\\n"), strings[1].escapes.map { it.text() })
        assertEquals(listOf("{x!r}"), strings[1].interpolations.map { it.text() })
        assertNull(strings[2].strippedIndent)
        assertEquals(12, strings[3].strippedIndent)
    }

    @Test
    fun `a reply that does not fit the document is not read`() = onEdt {
        val response = Gson().fromJson(resource("golden.json"), BySyntaxOutlineResponse::class.java)
        fixture.configureByText("short.by", "x = 1\n")
        assertNull(ByOutlineReplies.read(response, fixture.editor.document))
    }
}
