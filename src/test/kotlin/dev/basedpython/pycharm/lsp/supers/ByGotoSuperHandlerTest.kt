package dev.basedpython.pycharm.lsp.supers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How Go to Super asks, rather than what it asks: `by` may answer only once the platform has sent
 * it the file, and the platform sends that from the EDT in the non-modal state — so the asking must
 * leave that state free, and an answer that comes after the user has moved on is not shown.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByGotoSuperHandlerTest {

    private val fixture by codeInsightFixture()

    private val nowhere = BySuperAnswer.Nowhere("nowhere")

    /** Runs the EDT until [job] is done, as the IDE would while the answer is out. */
    private fun waitFor(job: Job) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!job.isCompleted && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue(job.isCompleted, "Go to Super never finished asking")
    }

    @Test
    fun `the platform's non-modal work runs while by is asked, so a didOpen by waits for goes out`() {
        fixture.configureByText("shapes.by", "class Shape:\n    pass\n")
        var nonModalRan = false
        val shown = mutableListOf<BySuperAnswer>()

        val job = ByGotoSuperHandler().ask(fixture.project, fixture.editor, { _, _ ->
            // what the platform's didOpen waits for: the EDT, in the non-modal state
            val ran = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater({ ran.complete(Unit) }, ModalityState.nonModal())
            nonModalRan = withTimeoutOrNull(3_000) { ran.await() } != null
            nowhere
        }) { shown += it }
        waitFor(job)

        assertTrue(nonModalRan, "the EDT was held in a modal state while by was asked")
        assertEquals(listOf<BySuperAnswer>(nowhere), shown)
    }

    @Test
    fun `an answer for text the user has since changed is not shown`() {
        fixture.configureByText("shapes.by", "class Shape:\n    pass\n")
        val document = fixture.editor.document
        var editedMeanwhile = false
        val shown = mutableListOf<BySuperAnswer>()

        val job = ByGotoSuperHandler().ask(fixture.project, fixture.editor, { _, _ ->
            val edited = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater({
                WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "z = 1\n") }
                edited.complete(Unit)
            }, ModalityState.nonModal())
            editedMeanwhile = withTimeoutOrNull(3_000) { edited.await() } != null
            nowhere
        }) { shown += it }
        waitFor(job)

        assertTrue(editedMeanwhile, "the EDT was held in a modal state while by was asked")
        assertEquals(emptyList<BySuperAnswer>(), shown)
    }

    @Test
    fun `an answer for a caret the user has since moved is not shown`() {
        fixture.configureByText("shapes.by", "class Shape:\n    pass\n")
        val editor = fixture.editor
        var movedMeanwhile = false
        val shown = mutableListOf<BySuperAnswer>()

        val job = ByGotoSuperHandler().ask(fixture.project, editor, { _, _ ->
            val moved = CompletableDeferred<Unit>()
            ApplicationManager.getApplication().invokeLater({
                editor.caretModel.moveToOffset(editor.document.textLength)
                moved.complete(Unit)
            }, ModalityState.nonModal())
            movedMeanwhile = withTimeoutOrNull(3_000) { moved.await() } != null
            nowhere
        }) { shown += it }
        waitFor(job)

        assertTrue(movedMeanwhile, "the EDT was held in a modal state while by was asked")
        assertEquals(emptyList<BySuperAnswer>(), shown)
    }
}
