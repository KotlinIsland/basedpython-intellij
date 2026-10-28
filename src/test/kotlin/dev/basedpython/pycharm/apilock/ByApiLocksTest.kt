package dev.basedpython.pycharm.apilock

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.runInEdtAndWait
import dev.basedpython.pycharm.lsp.ext.ByApiLock
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The project's `api.lock` written where `by/apiLock` says the project keeps it, as `by` generated
 * it.
 */
@TestFixtures
class ByApiLocksTest {

    private val fixture by codeInsightFixture()

    private val text = "#api-lock:v=1\n#tool:by=0.0.19\n#python:default\n#modules:1\napiproj.Shape:c[]\napiproj.area:d()->builtins.int\n"

    private fun lockIn(dir: Path) = ByApiLock(uri = dir.resolve("api.lock").toUri().toString(), text = text, python = "default", exists = false)

    @Test
    fun `a project with no lockfile gets one`(@TempDir dir: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
        val file = runBlocking { ByApiLocks.write(fixture.project, lockIn(dir)) }
        assertEquals("api.lock", file?.name)
        assertEquals(text, VfsUtil.loadText(file!!))
    }

    @Test
    fun `a stale lockfile is rewritten and saved, one an editor holds included`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("api.lock"), "#api-lock:v=1\nold\n")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("api.lock"))!!
        runInEdtAndWait { FileDocumentManager.getInstance().getDocument(vf) }
        runBlocking { ByApiLocks.write(fixture.project, lockIn(dir)) }
        // what the IDE holds as the file's content once saved; measured, the platform can be some
        // milliseconds behind putting it on the disk itself
        assertEquals(text, VfsUtil.loadText(vf))
        runInEdtAndWait { assertFalse(FileDocumentManager.getInstance().isFileModified(vf)) }
    }

    @Test
    fun `records are the lines that are not headers`() {
        assertEquals(2, ByApiLocks.records(text))
    }
}
