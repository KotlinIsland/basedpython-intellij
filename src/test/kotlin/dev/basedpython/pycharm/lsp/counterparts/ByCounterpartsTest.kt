package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByCounterpartDeclaration
import dev.basedpython.pycharm.lsp.ext.ByCounterpartModule
import dev.basedpython.pycharm.lsp.ext.ByCounterpartTarget
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterpartsParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** `by/documentCounterparts` on the wire, and what asking it comes to. */
@TestFixtures
class ByCounterpartsTest {

    private val fixture by codeInsightFixture()

    /** lsp4j's own Gson: the json the plugin puts on the wire and reads off it. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    @Test
    fun `the params are the request's, with the text they are about`() {
        assertEquals(
            """{"textDocument":{"uri":"file:///p/mod.py"},"textHash":"00000000000000ff"}""",
            gson.toJson(ByDocumentCounterpartsParams(TextDocumentIdentifier("file:///p/mod.py"), "00000000000000ff")),
        )
    }

    /** The example of the contract, as `by` writes it. */
    @Test
    fun `the answer reads as the contract writes it`() {
        val json = """
            {"role":"implementation","counterparts":[{"uri":"file:///p/mod.pyi"}],
             "declarations":[{"name":"A.f","kind":"function",
               "selectionRange":{"start":{"line":1,"character":8},"end":{"line":1,"character":9}},
               "targets":[{"uri":"file:///p/mod.pyi",
                 "range":{"start":{"line":3,"character":4},"end":{"line":3,"character":36}},
                 "selectionRange":{"start":{"line":3,"character":8},"end":{"line":3,"character":9}}}]}]}
        """.trimIndent()
        val read = gson.fromJson(json, ByDocumentCounterparts::class.java)
        assertEquals(
            ByDocumentCounterparts(
                role = "implementation",
                counterparts = listOf(ByCounterpartModule("file:///p/mod.pyi")),
                declarations = listOf(
                    ByCounterpartDeclaration(
                        name = "A.f",
                        kind = "function",
                        selectionRange = range(1, 8, 9),
                        targets = listOf(ByCounterpartTarget("file:///p/mod.pyi", range(3, 4, 36), range(3, 8, 9))),
                    ),
                ),
            ),
            read,
        )
        assertEquals(false, read.isStub)
        assertEquals(true, read.copy(role = "stub").isStub)
    }

    private fun ask(answer: (String) -> CompletableFuture<*>): Pair<ByCounterparts.Asked, AnsweringClient> = runBlocking {
        val file = fixture.configureByText("mod.py", "x = 1\n")
        val client = AnsweringClient(fixture.project) { answer(it) }
        ByCounterparts.ask(client, file.virtualFile, "00000000000000ff") to client
    }

    @Test
    fun `one request asks about the document and names its text`() = onEdt {
        val methods = mutableListOf<String>()
        val (asked, client) = ask { methods += it; answered(ByDocumentCounterparts("stub", listOf(ByCounterpartModule("file:///p/mod.py")))) }
        assertEquals(listOf("by/documentCounterparts"), methods)
        assertEquals("00000000000000ff", (client.sentParams.single() as ByDocumentCounterpartsParams).textHash)
        assertEquals(
            ByCounterparts.Asked.Answered(ByDocumentCounterparts("stub", listOf(ByCounterpartModule("file:///p/mod.py")))),
            asked,
        )
    }

    @Test
    fun `null is a document that is neither a stub nor has one`() = onEdt {
        assertEquals(ByCounterparts.Asked.Answered(null), ask { answered<Any>(null) }.first)
    }

    @Test
    fun `a by without the request is told apart from one that failed`() = onEdt {
        assertEquals(
            ByCounterparts.Asked.Unknown,
            ask { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/documentCounterparts") }.first,
        )
        assertEquals(ByCounterparts.Asked.Failed, ask { failed(ResponseErrorCode.InternalError) }.first)
    }

    /** A test's answer stands for `by`'s while the text is what it was answered for, and no longer. */
    @Test
    fun `an answer is for the text it was given for`() = onEdt {
        val file = fixture.configureByText("mod.by", "def f(): ...\n")
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val answer = ByDocumentCounterparts("implementation", listOf(ByCounterpartModule("file:///p/mod.pyi")))
        val counterparts = ByCounterparts.getInstance(fixture.project)
        counterparts.remember(document, file.virtualFile, answer)
        assertEquals(answer, offEdt { counterparts.forFile(file) })

        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "\n") }
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
        // no server in a test: the edited text has nobody to answer for it
        assertNull(offEdt { counterparts.forFile(file) })
    }

    /** An answer that names no other side is not one: nothing can be drawn from it. */
    @Test
    fun `an answer without counterparts is nothing`() = onEdt {
        val file = fixture.configureByText("mod.by", "def f(): ...\n")
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        ByCounterparts.getInstance(fixture.project).remember(document, file.virtualFile, ByDocumentCounterparts("stub", emptyList()))
        assertNull(offEdt { ByCounterparts.getInstance(fixture.project).forFile(file) })
    }
}
