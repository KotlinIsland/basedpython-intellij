package dev.basedpython.pycharm.lsp

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.RecordingByClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The document lifecycle [ByServerDocuments] runs for files the platform's client will not sync.
 *
 * Asserted as the notifications a server would receive, in order, because that is the whole
 * contract: a server that was opened on a file and never told it changed answers about text that is
 * no longer there, and one told to open a file twice refuses the second.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByServerDocumentsTest {

    private val fixture by codeInsightFixture()

    private val temp: Path = Files.createTempDirectory("server-documents-test")
    private val roots = mutableListOf<VirtualFile>()

    @AfterEach
    fun cleanUp() {
        roots.forEach { PsiTestUtil.removeContentEntry(fixture.module, it) }
        temp.toFile().deleteRecursively()
    }

    private val documents get() = fixture.project.service<ByServerDocuments>()

    /** A file on disk outside every content root — the shape of a scratch file or a stub. */
    private fun outside(name: String, text: String): VirtualFile {
        val path = Files.createDirectories(temp.resolve("outside")).resolve(name)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private fun ensureOpen(client: LspClient, file: VirtualFile) =
        ReadAction.run<RuntimeException> { documents.ensureOpen(client, file) }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, text) }
    }

    @Test
    fun `a file outside the content roots is opened once and told every edit`() {
        val client = RecordingByClient(fixture.project)
        val file = outside("scratch.by", "x = 1\n")

        ensureOpen(client, file)
        ensureOpen(client, file)
        edit(file, "y = 2\n")
        edit(file, "z = 3\n")

        assertEquals(
            listOf(
                "open scratch.by v1 x = 1",
                "change scratch.by v2 y = 2\nx = 1",
                "change scratch.by v3 z = 3\ny = 2\nx = 1",
            ),
            client.sent,
        )
    }

    @Test
    fun `a file under a content root is the platform's to sync`() {
        val client = RecordingByClient(fixture.project)
        val file = fixture.configureByText("content.by", "x = 1\n").virtualFile

        ensureOpen(client, file)
        edit(file, "y = 2\n")

        assertEquals(emptyList<String>(), client.sent)
    }

    @Test
    fun `a file that becomes content is closed before the platform opens it`() {
        val client = RecordingByClient(fixture.project)
        val file = outside("becomes.by", "x = 1\n")
        ensureOpen(client, file)

        PsiTestUtil.addContentRoot(fixture.module, file.parent)
        roots += file.parent
        ensureOpen(client, file)
        edit(file, "y = 2\n")

        assertEquals(listOf("open becomes.by v1 x = 1", "close becomes.by"), client.sent)
    }

    @Test
    fun `closing the last editor on a file closes it, and asking again opens it again`() {
        val client = RecordingByClient(fixture.project)
        val file = outside("closed.by", "x = 1\n")
        fixture.openFileInEditor(file)
        ensureOpen(client, file)

        FileEditorManager.getInstance(fixture.project).closeFile(file)
        ensureOpen(client, file)

        assertEquals(listOf("open closed.by v1 x = 1", "close closed.by", "open closed.by v1 x = 1"), client.sent)
    }

    @Test
    fun `a deleted file is closed under the URI it was opened with`() {
        val client = RecordingByClient(fixture.project)
        val file = outside("deleted.by", "x = 1\n")
        ensureOpen(client, file)

        WriteAction.run<Exception> { file.delete(this) }

        assertEquals(listOf("open deleted.by v1 x = 1", "close deleted.by"), client.sent)
    }
}
