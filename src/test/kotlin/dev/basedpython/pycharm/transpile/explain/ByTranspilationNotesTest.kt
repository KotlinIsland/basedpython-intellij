package dev.basedpython.pycharm.transpile.explain

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByTranspilationNote
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * What *Explain Transpilation* tells the user, from what `by/explainTranspilation` came back with.
 *
 * `by` answers `null` for a document it has no file of its own for, and that is an answer; an error,
 * a server that is not running and an edit landing mid-request are not, and each reads differently.
 */
@TestFixtures
class ByTranspilationNotesTest {

    private val fixture by codeInsightFixture()

    private val told = mutableListOf<String>()

    private fun notes(running: Boolean = true, answer: () -> CompletableFuture<*>): List<ByTranspilationNote>? {
        fixture.project.messageBus.connect(fixture.testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    told += notification.content
                }
            },
        )
        val file = fixture.configureByText("a.by", "x = a?.b\n").virtualFile
        return ByTranspilationNotes.of(fixture.project, file, AnsweringClient(fixture.project, running) { answer() })
    }

    private val note = ByTranspilationNote(construct = "null-safe access", snippet = "a?.b", explanation = "…")

    @Test
    fun `the constructs by names are the notes`() = onEdt {
        assertEquals(listOf(note), notes { answered(listOf(note)) })
        assertEquals(emptyList<String>(), told)
    }

    @Test
    fun `by having no file for the document is said as that`() = onEdt {
        assertNull(notes { answered(null) })
        assertEquals(listOf(BasedPythonBundle.message("transpile.serverHasNoFile")), told)
    }

    @Test
    fun `an error answer is a server that did not answer`() = onEdt {
        assertNull(notes { failed(ResponseErrorCode.InvalidParams, "Document is not open in the session") })
        assertEquals(listOf(BasedPythonBundle.message("transpile.serverDidNotAnswer")), told)
    }

    @Test
    fun `a server that is not running did not answer either`() = onEdt {
        assertNull(notes(running = false) { answered(listOf(note)) })
        assertEquals(listOf(BasedPythonBundle.message("transpile.serverDidNotAnswer")), told)
    }

    @Test
    fun `an edit landing mid-request is asked again`() = onEdt {
        var asked = 0
        val answer = notes { if (++asked == 1) failed(ResponseErrorCode.ContentModified) else answered(listOf(note)) }
        assertEquals(listOf(note), answer)
        assertEquals(2, asked)
        assertEquals(emptyList<String>(), told)
    }
}
