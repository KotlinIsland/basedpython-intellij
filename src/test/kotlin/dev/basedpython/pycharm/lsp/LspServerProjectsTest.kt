package dev.basedpython.pycharm.lsp

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.testFramework.withLocksForbidden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Which open project's servers a file is given to, with two projects open.
 *
 * The platform offers an edited, unsaved document to every running client of every open project,
 * and opens it on each whose descriptor's `isSupportedFile` says yes. Measured in the runIde
 * sandbox with three projects open: editing `mod.py` in one sent its `didOpen` and `didChange`s to
 * `by` and `buff` in another, where `by` answered each `didChange` with *document not found for
 * key* and a *ty encountered a problem* balloon. The descriptors said yes by extension alone.
 */
@TestApplication
@TestFixtures
class LspServerProjectsTest {

    private val based by projectFixture(openAfterCreation = true)
    private val other by projectFixture(openAfterCreation = true)

    /**
     * Somewhere no project's base directory holds. Not `Files.createTempFile`: the JVM's temp
     * directory is the light test project's base.
     */
    private val elsewhere by tempPathFixture()

    private fun fileIn(base: String?, relative: String, text: String = ""): VirtualFile {
        val path = Path.of(base!!).resolve(relative)
        path.parent.createDirectories()
        path.writeText(text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    private val launch = ByLaunch(Path.of("/nonexistent/by"), emptyList(), emptyMap(), venvRoot = null, kind = ByEnvironmentKind.PATH)

    /** Which of `by` and `buff` in [project] say they support each of [files]. */
    private fun supported(project: Project, files: List<VirtualFile>): List<String> {
        val by = ByLspServerDescriptor(project, launch, emptyList())
        val buff = BuffLspServerDescriptor(project, launch, emptyList())
        return files.map { file ->
            listOfNotNull("by".takeIf { by.isSupportedFile(file) }, "buff".takeIf { buff.isSupportedFile(file) })
                .joinToString("+").ifEmpty { "-" }
        }
    }

    @Test
    fun `each project's servers are given its own files and not the other's, without a lock`() {
        val mine = listOf(fileIn(based.basePath, "pkg/mod.py"), fileIn(based.basePath, "main.by"), fileIn(based.basePath, API_LOCK))
        val theirs = listOf(fileIn(other.basePath, "mod.py"), fileIn(other.basePath, "main.by"), fileIn(other.basePath, API_LOCK))
        val files = mine + theirs

        val (inBased, inOther) = withLocksForbidden { supported(based, files) to supported(other, files) }

        assertEquals(listOf("by+buff", "by+buff", "by", "-", "-", "-"), inBased)
        assertEquals(listOf("-", "-", "-", "by+buff", "by+buff", "by"), inOther)
    }

    @Test
    fun `a file outside both projects is the one whose editor has it open, or both's`() {
        val opened = fileIn(elsewhere.toString(), "opened.py")
        val nobodys = fileIn(elsewhere.toString(), "nobodys.py")
        onEdt { FileEditorManagerEx.getInstanceEx(other).openFile(opened, false) }
        try {
            val files = listOf(opened, nobodys)

            val (inBased, inOther) = withLocksForbidden { supported(based, files) to supported(other, files) }

            assertEquals(listOf("-", "by+buff"), inBased)
            assertEquals(listOf("by+buff", "by+buff"), inOther)
        } finally {
            onEdt { FileEditorManager.getInstance(other).closeFile(opened) }
        }
    }
}
