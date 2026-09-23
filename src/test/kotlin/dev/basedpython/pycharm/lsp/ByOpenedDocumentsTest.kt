package dev.basedpython.pycharm.lsp

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

/**
 * When a `by` server counts as holding a document: from the moment the platform builds its `didOpen`
 * (which it does through the descriptor's `getLanguageId`) until the platform would close it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByOpenedDocumentsTest {

    private val fixture by codeInsightFixture()

    private fun descriptor() = ByLspServerDescriptor(
        fixture.project,
        ByLaunch(Paths.get("/nonexistent/by"), emptyList(), emptyMap(), venvRoot = null, kind = ByEnvironmentKind.PATH),
        emptyList(),
    )

    private fun client(descriptor: ByLspServerDescriptor = descriptor()) =
        AnsweringClient(fixture.project, descriptor = descriptor) { CompletableFuture.completedFuture(null) }

    /** The platform's `didOpen` for [file] on [client]'s server, as far as the descriptor sees it. */
    private fun platformOpens(client: AnsweringClient, file: VirtualFile) {
        WriteAction.run<RuntimeException> { client.descriptor.getLanguageId(file) }
    }

    private fun opened(): MutableList<VirtualFile> {
        val heard = mutableListOf<VirtualFile>()
        fixture.project.messageBus.connect(fixture.testRootDisposable)
            .subscribe(ByOpenedDocuments.Listener.TOPIC, ByOpenedDocuments.Listener { heard += it })
        return heard
    }

    @Test
    fun `a file is held once the platform builds its didOpen, and that is said once`() {
        val heard = opened()
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val client = client()
        assertFalse(client.hasDocument(file))

        platformOpens(client, file)
        platformOpens(client, file)

        assertTrue(client.hasDocument(file))
        assertEquals(listOf(file), heard)
    }

    @Test
    fun `one server's didOpen is not another's`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val first = client()
        platformOpens(first, file)
        assertFalse(client().hasDocument(file))
    }

    @Test
    fun `the last editor closing on a saved file lets it go`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val client = client()
        platformOpens(client, file)

        FileEditorManager.getInstance(fixture.project).closeFile(file)

        assertFalse(client.hasDocument(file))
    }

    @Test
    fun `an unsaved file stays open when its editor closes, and goes when it is saved`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val client = client()
        platformOpens(client, file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "# typed\n") }

        FileEditorManager.getInstance(fixture.project).closeFile(file)
        assertTrue(client.hasDocument(file))

        FileDocumentManager.getInstance().saveDocument(document)
        assertFalse(client.hasDocument(file))
    }

    @Test
    fun `a deleted file is let go`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val client = client()
        platformOpens(client, file)

        WriteAction.run<Exception> { file.delete(this) }

        assertFalse(client.hasDocument(file))
    }

    @Test
    fun `a server that stops, or starts again, holds nothing`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val descriptor = descriptor()
        val client = client(descriptor)

        platformOpens(client, file)
        fixture.project.messageBus.syncPublisher(ByLspLifecycleListener.TOPIC).serverStopped("buff", true)
        assertTrue(client.hasDocument(file), "buff stopping says nothing about by")
        fixture.project.messageBus.syncPublisher(ByLspLifecycleListener.TOPIC).serverStopped("by", false)
        assertFalse(client.hasDocument(file))

        platformOpens(client, file)
        ByOpenedDocuments.getInstance(fixture.project).starting(descriptor)
        assertFalse(client.hasDocument(file))
    }

    @Test
    fun `a client this plugin did not start is taken to hold what it is asked about`() {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        assertTrue(AnsweringClient(fixture.project) { CompletableFuture.completedFuture(null) }.hasDocument(file))
    }
}
