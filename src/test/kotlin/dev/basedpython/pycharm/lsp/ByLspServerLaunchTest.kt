package dev.basedpython.pycharm.lsp

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Which `by` and `buff` a project's language servers run.
 *
 * The descriptors are project-wide, and the platform identifies a server by its descriptor's class,
 * name and roots — so for a project there is one of each, and whichever descriptor asked first is
 * the one that runs. What a descriptor carries must therefore not depend on the file that happened
 * to ask, or the server a project gets depends on the order its files were opened in.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByLspServerLaunchTest {

    private val fixture by codeInsightFixture()

    private val temp: Path = Files.createTempDirectory("by-launch-test")
    private val roots = mutableListOf<VirtualFile>()

    @AfterEach
    fun cleanUp() {
        roots.forEach { PsiTestUtil.removeContentEntry(fixture.module, it) }
        temp.toFile().deleteRecursively()
    }

    /** A content root of its own with a `.venv` holding [binaries], and a `.by` file in it. */
    private fun module(name: String, vararg binaries: String): VirtualFile {
        val dir = Files.createDirectories(temp.resolve(name))
        for (binary in binaries) {
            val exe = Files.createDirectories(dir.resolve(".venv/bin")).resolve(binary)
            Files.writeString(exe, "#!/bin/sh\n")
            Files.setPosixFilePermissions(exe, PosixFilePermission.entries.toSet())
        }
        Files.writeString(dir.resolve("main.by"), "x = 1\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
        PsiTestUtil.addContentRoot(fixture.module, root)
        roots += root
        return root.findChild("main.by")!!
    }

    private fun descriptorFor(provider: LspIntegrationProvider, file: VirtualFile): LspClientDescriptor? {
        var started: LspClientDescriptor? = null
        provider.fileOpened(
            fixture.project,
            file,
            object : LspIntegrationProvider.LspClientStarter {
                override fun ensureClientStarted(descriptor: LspClientDescriptor) {
                    started = descriptor
                }
            },
        )
        return started
    }

    private fun LspClientDescriptor.command(): String = when (this) {
        is ByLspServerDescriptor -> createCommandLine().commandLineString
        is BuffLspServerDescriptor -> createCommandLine().commandLineString
        else -> error("not one of ours: $this")
    }

    @Test
    fun `the server a project runs does not depend on which module opened a file first`() {
        val first = module("first", "by", "buff")
        val second = module("second", "by", "buff")
        // What the old resolution would have picked, so this is not passing on two identical roots.
        assertNotEquals(
            BasedPythonBinaries.launchBy(fixture.project, first)?.exe,
            BasedPythonBinaries.launchBy(fixture.project, second)?.exe,
        )

        for (provider in listOf(ByLspServerSupportProvider(), BuffLspServerSupportProvider())) {
            val fromFirst = descriptorFor(provider, first)?.command()
            val fromSecond = descriptorFor(provider, second)?.command()
            assertEquals(fromFirst, fromSecond, provider.javaClass.simpleName)
        }
    }
}
