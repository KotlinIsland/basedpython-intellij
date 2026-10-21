package dev.basedpython.pycharm.lsp.supers

import com.intellij.lang.CodeInsightActions
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByOverridableMember
import dev.basedpython.pycharm.lsp.ext.ByOverridableMembers
import dev.basedpython.pycharm.lsp.ext.ByOverrideMembers as ByOverrideEdit
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Override and Implement Methods decide nothing about the source: what a class can override and
 * what is written for it both come from `by`. What is left here is offering `by`'s list the way the
 * platform's chooser groups it, telling the user why there is nothing to offer, and writing `by`'s
 * edit where it says, with the caret where it says.
 */
@TestFixtures
class ByOverrideMembersTest {

    private val fixture by codeInsightFixture()

    private val place = ByMembersPlace(TextDocumentIdentifier("file:///shapes.by"), "cbf29ce484222325", Position(4, 4))

    private val area = ByOverridableMember("area", "Shape", "shapes", abstract = true, kind = "method", signature = "area(self) -> float")
    private val name = ByOverridableMember("name", "Shape", "shapes", kind = "method", signature = "name(self) -> str")
    private val repr = ByOverridableMember("__repr__", "object", "builtins", kind = "method", signature = "__repr__(self) -> str")

    @Test
    fun `both handlers are registered for basedpython, so Ctrl+O and Ctrl+I are no longer dead there`() = onEdt {
        assertInstanceOf(ByOverrideMethodsHandler::class.java, CodeInsightActions.OVERRIDE_METHOD.forLanguage(BasedPythonLanguage))
        assertInstanceOf(ByImplementMethodsHandler::class.java, CodeInsightActions.IMPLEMENT_METHOD.forLanguage(BasedPythonLanguage))
    }

    @Test
    fun `the actions are enabled anywhere in a by file, since only by can say whether the caret is in a class`() = onEdt {
        fixture.configureByText("shapes.by", "x = 1\n")
        val handler = CodeInsightActions.OVERRIDE_METHOD.forLanguage(BasedPythonLanguage) as LanguageCodeInsightActionHandler
        assertTrue(handler.isValidFor(fixture.editor, fixture.file))
    }

    private fun find(answer: (String) -> CompletableFuture<*>): Pair<ByMembersAnswer, List<String>> {
        val asked = mutableListOf<String>()
        val client = AnsweringClient(fixture.project) { method -> asked += method; answer(method) }
        return runBlocking { ByOverrideMembers.find(client, place) } to asked
    }

    @Test
    fun `what by lists is offered as by lists it, about the text the caret was read in`() = onEdt {
        val (answer, asked) = find { answered(ByOverridableMembers("Square", listOf(area, name, repr))) }
        assertEquals(listOf("by/overridableMembers"), asked)
        assertEquals(ByMembersAnswer.Members(place, "Square", listOf(area, name, repr)), answer)
    }

    @Test
    fun `no class at the caret is by's answer, and said as that`() = onEdt {
        val (answer, _) = find { answered(null) }
        assertEquals(ByMembersAnswer.Nothing(BasedPythonBundle.message("members.noClass")), answer)
    }

    @Test
    fun `a by without the request is said to need updating`() = onEdt {
        val (answer, _) = find { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/overridableMembers") }
        assertEquals(ByMembersAnswer.Nothing(BasedPythonBundle.message("members.unsupported", "by/overridableMembers")), answer)
    }

    @Test
    fun `any other error is a by that did not answer`() = onEdt {
        val (answer, _) = find { failed(ResponseErrorCode.InternalError) }
        assertEquals(ByMembersAnswer.Nothing(BasedPythonBundle.message("members.noAnswer", "by/overridableMembers")), answer)
    }

    @Test
    fun `the declarations are asked for by name, about the same place`() = onEdt {
        val edit = ByOverrideEdit(listOf(TextEdit(Range(Position(4, 4), Position(4, 4)), "x")), Range(Position(4, 4), Position(4, 5)))
        val client = AnsweringClient(fixture.project) { answered(edit) }
        val written = runBlocking { ByOverrideMembers.write(client, place, listOf("area")) }
        assertEquals(ByWrittenMembers.Edit(edit), written)

        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }
        assertEquals(
            ByWrittenMembers.Nothing(BasedPythonBundle.message("members.unsupported", "by/overrideMembers")),
            runBlocking { ByOverrideMembers.write(old, place, listOf("area")) },
        )
    }

    @Test
    fun `Override offers every member and Implement only the abstract ones, each under its superclass`() {
        val all = ByOverrideMembers.choices(ByMembersAction.Override, listOf(area, name, repr))
        assertEquals(listOf("area", "name", "__repr__"), all.map { it.name })
        assertSame(all[0].parentNodeDelegate, all[1].parentNodeDelegate)
        assertNotSame(all[0].parentNodeDelegate, all[2].parentNodeDelegate)
        assertEquals("area(self) -> float", all[0].text)

        assertEquals(listOf("area"), ByOverrideMembers.choices(ByMembersAction.Implement, listOf(area, name, repr)).map { it.name })
    }

    @Test
    fun `by's edit is written as one command, with its body selected`() = onEdt {
        fixture.configureByText("shapes.by", "class Square(Shape):\n    pass\n")
        val written = ByOverrideEdit(
            listOf(
                TextEdit(Range(Position(1, 4), Position(1, 8)), "override def area(self) -> float:\n        ..."),
                TextEdit(Range(Position(0, 0), Position(0, 0)), "from shapes import Shape\n"),
            ),
            Range(Position(3, 8), Position(3, 11)),
        )
        ByOverrideMembers.apply(fixture.project, fixture.editor, "Implement Methods", written)

        assertEquals(
            "from shapes import Shape\nclass Square(Shape):\n    override def area(self) -> float:\n        ...\n",
            fixture.editor.document.text,
        )
        assertEquals("...", fixture.editor.selectionModel.selectedText)
        assertEquals(fixture.editor.selectionModel.selectionStart, fixture.editor.caretModel.offset)
    }

    /** Runs the EDT until [job] is done, as the IDE would while the answer is out. */
    private fun waitFor(job: Job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(job.isCompleted, "the members were never asked for")
    }

    @Test
    fun `by is asked with the EDT free, and an answer for a caret since moved is not shown`() = onEdt {
        fixture.configureByText("shapes.by", "class Square(Shape):\n    pass\n")
        val editor = fixture.editor
        var nonModalRan = false
        val shown = mutableListOf<String>()

        val job = ByOverrideMethodsHandler().ask(fixture.project, editor, "asking", { _, _ ->
            // what the platform's didOpen waits for: the EDT, in the non-modal state
            val moved = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater({
                editor.caretModel.moveToOffset(editor.document.textLength)
                moved.complete(Unit)
            }, ModalityState.nonModal())
            nonModalRan = withTimeoutOrNull(3_000) { moved.await() } != null
            "answer"
        }) { shown += it }
        waitFor(job)

        assertTrue(nonModalRan, "the EDT was held in a modal state while by was asked")
        assertEquals(emptyList<String>(), shown)
    }
}
