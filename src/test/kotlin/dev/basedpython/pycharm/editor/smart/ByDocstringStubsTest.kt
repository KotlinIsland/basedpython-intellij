package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByDocstringFormat
import dev.basedpython.pycharm.lsp.ext.ByDocstringStub
import dev.basedpython.pycharm.lsp.ext.ByDocstringStubParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `"""` and Enter at the top of a function: which line Enter has just left is read from the text;
 * the stub, and whether the literal is a function's docstring at all, is `by`'s answer.
 */
@TestFixtures
class ByDocstringStubsTest {

    private val fixture by codeInsightFixture()

    /** Configures [text] and says what [ByDocstringStubs.openedAt] finds at its caret. */
    private fun opened(text: String): ByDocstringStubs.Opened? {
        fixture.configureByText("a.by", text)
        val editor = fixture.editor
        return ByDocstringStubs.openedAt(editor.document.immutableCharSequence, editor.caretModel.offset) {
            editor.highlighter.createIterator(it).start
        }
    }

    @Test
    fun `an empty docstring the quote handler closed`() = onEdt {
        val text = "def f(a):\n    \"\"\"\n    <caret>\"\"\"\n    return a\n"
        assertEquals(ByDocstringStubs.Opened("    ", "\"\"\"", text.replace("<caret>", "").indexOf("\"\"\"\n    return")), opened(text))
    }

    @Test
    fun `an opening that does not close is closed by the handler`() = onEdt {
        assertEquals(ByDocstringStubs.Opened("    ", "'''", null), opened("def f(a):\n    r'''\n    <caret>\n    return a\n"))
    }

    @Test
    fun `the closing quotes of a docstring are not an opening`() = onEdt {
        assertNull(opened("def f(a):\n    \"\"\"\n    Summary.\n    \"\"\"\n    <caret>\n    return a\n"))
    }

    @Test
    fun `nor is a string with text, an assigned string, or an f-string`() = onEdt {
        assertNull(opened("def f(a):\n    \"\"\"Summary\n    <caret>\"\"\"\n"))
        assertNull(opened("def f(a):\n    x = \"\"\"\n    <caret>\n"))
        assertNull(opened("def f(a):\n    f\"\"\"\n    <caret>\"\"\"\n"))
    }

    @Test
    fun `by's stub replaces the literal and the caret goes where by says`() = onEdt {
        fixture.configureByText("a.by", "def f(item):\n    \"\"\"\n    <caret>\"\"\"\n")
        val stub = ByDocstringStub(
            range = Range(Position(1, 4), Position(2, 7)),
            newText = "\"\"\"\n    \n\n    :param item:\n    \"\"\"",
            caret = Position(2, 4),
        )
        val job = fixture.project.service<ByDocstringStubs>().fill(fixture.editor) { _, _ -> stub }
        job.join()
        assertEquals("def f(item):\n    \"\"\"\n    \n\n    :param item:\n    \"\"\"\n", fixture.editor.document.text)
        assertEquals(fixture.editor.document.getLineStartOffset(2) + 4, fixture.editor.caretModel.offset)
    }

    /** What [ByDocstringStubs.ask] sends `by` for the docstring at the caret of [text]. */
    private suspend fun sentFor(text: String): ByDocstringStubParams {
        fixture.configureByText("a.by", text)
        val client = AnsweringClient(fixture.project) { answered(null) }
        val file = fixture.file.virtualFile
        val document = fixture.editor.document
        val offset = fixture.editor.caretModel.offset
        withContext(Dispatchers.Default) { ByDocstringStubs.ask(client, file, document, offset) }
        return client.sentParams.single() as ByDocstringStubParams
    }

    @Test
    fun `with no format in the IDE's settings, by picks`() = onEdt {
        // an IDE without the Python plugin, which is the only one to register a provider
        assertEquals(emptyList<ByDocstringFormatProvider>(), ByDocstringFormatProvider.EP_NAME.extensionList)
        assertNull(sentFor("def f(a):\n    \"\"\"\n    <caret>\"\"\"\n").format)
    }

    @Test
    fun `the format the IDE's settings name is the one by is asked for`() = onEdt {
        val google = object : ByDocstringFormatProvider {
            override fun format(file: PsiFile) = ByDocstringFormat.GOOGLE
        }
        ByDocstringFormatProvider.EP_NAME.point.registerExtension(google, fixture.testRootDisposable)
        assertEquals("google", sentFor("def f(a):\n    \"\"\"\n    <caret>\"\"\"\n").format)
    }

    /** `DocstringStubParams` is `deny_unknown_fields`, and its `format` one of `StubFormat`'s camelCase names. */
    @Test
    fun `the format goes on the wire as by names it, and not at all when by picks`() {
        val gson = MessageJsonHandler(emptyMap()).gson
        val position = Position(1, 4)
        val document = TextDocumentIdentifier("file:///p/a.by")
        assertEquals(
            """{"textDocument":{"uri":"file:///p/a.by"},"position":{"line":1,"character":4},"textHash":"h","format":"numpy"}""",
            gson.toJson(ByDocstringStubParams(document, position, "h", ByDocstringFormat.NUMPY.wire)),
        )
        assertEquals(
            """{"textDocument":{"uri":"file:///p/a.by"},"position":{"line":1,"character":4},"textHash":"h"}""",
            gson.toJson(ByDocstringStubParams(document, position, "h")),
        )
        assertEquals(listOf("rest", "google", "numpy", "plain"), ByDocstringFormat.entries.map { it.wire })
    }
}
