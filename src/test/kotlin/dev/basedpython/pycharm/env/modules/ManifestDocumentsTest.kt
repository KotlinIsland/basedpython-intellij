package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The module operations' own manifest edits, against a manifest the editor has open.
 *
 * They used to be `Files.writeString`, which left an open editor's document saying what the file
 * said before — and the user's next save of that document put it back, deleting the edit. Writing
 * through the document instead was tried and measured flaky: a save right after uv rewrote the file
 * is vetoed as a disk conflict without a word.
 */
@TestFixtures
class ManifestDocumentsTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `an edit to an open manifest lands in its document and on disk`(@TempDir dir: Path) {
        val manifest = dir.resolve("pyproject.toml")
        Files.writeString(manifest, "[project]\nname = \"alpha\"\n")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest))
        val documents = FileDocumentManager.getInstance()
        val document = ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document> {
            checkNotNull(documents.getDocument(file))
        }

        ManifestDocuments.write(fixture.project, manifest, "[project]\nname = \"omega\"\n")

        assertEquals("[project]\nname = \"omega\"\n", document.text, "the open document shows the edit")
        assertEquals("[project]\nname = \"omega\"\n", Files.readString(manifest), "and it is on disk for uv")
        assertFalse(documents.isDocumentUnsaved(document))
        assertEquals("[project]\nname = \"omega\"\n", ManifestDocuments.read(manifest))
    }

    /** A rename rolled back removes a lock file it created, and restores one it deleted. */
    @Test
    fun `a file can be created and removed`(@TempDir dir: Path) {
        val lock = dir.resolve("uv.lock")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)

        ManifestDocuments.write(fixture.project, lock, "version = 1\n")
        assertEquals("version = 1\n", Files.readString(lock))

        ManifestDocuments.write(fixture.project, lock, null)
        assertFalse(Files.exists(lock))
        assertNull(ManifestDocuments.read(lock))
    }

    /**
     * The shape every rename step has: uv rewrites a manifest the IDE has a document for, and the
     * plugin's own edit follows. Without re-reading the file first the edit would be built on the
     * text from before uv wrote.
     */
    @Test
    fun `an edit after another process rewrote the file builds on what that process wrote`(@TempDir dir: Path) {
        val manifest = dir.resolve("pyproject.toml")
        Files.writeString(manifest, "[project]\nname = \"alpha\"\n")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest))
        ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document> {
            checkNotNull(FileDocumentManager.getInstance().getDocument(file))
        }
        // uv, behind the IDE's back.
        Files.writeString(manifest, "[project]\nname = \"alpha\"\ndependencies = [\"httpx\"]\n")

        val read = checkNotNull(ManifestDocuments.read(manifest))
        assertEquals("[project]\nname = \"alpha\"\ndependencies = [\"httpx\"]\n", read)
        ManifestDocuments.write(fixture.project, manifest, read.replace("alpha", "omega"))

        assertEquals("[project]\nname = \"omega\"\ndependencies = [\"httpx\"]\n", Files.readString(manifest))
    }

    /**
     * Unsaved text in an editor is something the user typed while the operation ran; it is neither
     * overwritten nor merged, and the edit reports that it could not be made.
     */
    @Test
    fun `a manifest with unsaved edits is refused, not overwritten`(@TempDir dir: Path) {
        val manifest = dir.resolve("pyproject.toml")
        Files.writeString(manifest, "on disk\n")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest))
        ApplicationManager.getApplication().invokeAndWait {
            WriteAction.run<RuntimeException> {
                checkNotNull(FileDocumentManager.getInstance().getDocument(file)).setText("in the editor\n")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            ManifestDocuments.write(fixture.project, manifest, "from the plugin\n")
        }
        assertEquals("on disk\n", Files.readString(manifest))
        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().reloadFromDisk(checkNotNull(FileDocumentManager.getInstance().getCachedDocument(file)))
        }
    }
}
