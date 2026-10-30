package dev.basedpython.pycharm.debug

import com.intellij.execution.Platform
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.run.target.ByTargetPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A debug session on a run target is in the target's paths — `by run`'s source maps name the files
 * where it ran, `/opt/project/pkg/main.by` from a Docker target (measured) — and its files are the
 * project's: a frame there opens the project's file, and a breakpoint here is sent there.
 */
@TestApplication
class ByTargetFileResolverTest {

    @TempDir
    lateinit var root: Path

    private fun file(): com.intellij.openapi.vfs.VirtualFile {
        val path = root.resolve("pkg/main.by")
        Files.createDirectories(path.parent)
        Files.writeString(path, "x = 1\n")
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    @Test
    fun `the target's paths are the project's files, both ways`() {
        val file = file()
        val resolver = ByTargetFileResolver { ByTargetPaths(root, "/opt/project", Platform.UNIX) }

        assertEquals(file, resolver.resolve("/opt/project/pkg/main.by"))
        assertEquals("/opt/project/pkg/main.by", resolver.getAdapterPath(file))
        assertNull(resolver.resolve("/usr/lib/python3.14/os.py"), "outside the project was never uploaded")
    }

    @Test
    fun `without a target it is the platform's own resolver`() {
        val file = file()
        val resolver = ByTargetFileResolver { null }
        assertEquals(file, resolver.resolve(file.path))
        assertEquals(file.path, resolver.getAdapterPath(file))
    }
}
