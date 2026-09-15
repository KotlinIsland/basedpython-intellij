package dev.basedpython.pycharm.tasks

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Which file changes re-scan a project's tasks: its own root's configuration files, and nothing else. */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTaskSyncActivityTest {

    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var dir: Path

    private val base = "/work/app"

    @Test
    fun `a configuration file at the project root is a change`() {
        assertTrue(touchesTaskConfig(listOf("/work/app/.pre-commit-config.yaml"), base))
        assertTrue(touchesTaskConfig(listOf("/work/app/pyproject.toml"), "/work/app/"))
    }

    @Test
    fun `the same name in another project is not`() {
        // The VFS topic is application-wide: this project hears the other one's saves.
        assertFalse(touchesTaskConfig(listOf("/work/other/pyproject.toml"), base))
        assertFalse(touchesTaskConfig(listOf("/work/app-2/pyproject.toml"), base))
    }

    @Test
    fun `the same name below the root is not either, since a scan never reads it`() {
        assertFalse(touchesTaskConfig(listOf("/work/app/packages/lib/pyproject.toml"), base))
    }

    @Test
    fun `a file of another name at the root is not`() {
        assertFalse(touchesTaskConfig(listOf("/work/app/setup.cfg"), base))
    }

    @Test
    fun `renaming a file to a configuration name is a change`() {
        val file = local("hooks.yaml")

        val paths = eventPaths(VFilePropertyChangeEvent(null, file, VirtualFile.PROP_NAME, "hooks.yaml", "lefthook.yml"))

        assertEquals(listOf(file.path, "${file.parent.path}/lefthook.yml"), paths)
        assertTrue(touchesTaskConfig(paths, file.parent.path))
    }

    @Test
    fun `moving a configuration file in or out of the root is a change`() {
        val sub = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.createDirectory(dir.resolve("sub"))))
        val file = local("pyproject.toml")

        val paths = eventPaths(VFileMoveEvent(null, file, sub))

        assertTrue(touchesTaskConfig(paths, file.parent.path))
    }

    private fun local(name: String): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(dir.resolve(name), "")))
}
