package dev.basedpython.pycharm.vcs

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.vcs.VcsEnvCustomizer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * [ByVcsEnvCustomizer] where git calls it from: anywhere a git command starts, including where locks
 * are prohibited.
 */
@TestFixtures
class ByVcsEnvCustomizerTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val created = mutableListOf<String>()

    private fun baseDir(): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectories(project.basePath!!) }

    private fun makeBasedPythonProject() = onEdt {
        val settings = BasedPythonSettings.getInstance(project)
        settings.byEnabled = true
        settings.pyFileHandling = PyFileHandling.ALWAYS
        WriteAction.runAndWait<RuntimeException> {
            if (baseDir().findChild("api.lock") == null) {
                baseDir().createChildData(this, "api.lock")
                created += "api.lock"
            }
        }
    }

    /** A `.venv` at the project base with an executable `by` in it, as `uv sync` leaves one. */
    private fun venvWithBy(): Path {
        val bin = Path.of(project.basePath!!, ".venv", "bin")
        Files.createDirectories(bin)
        created += ".venv"
        val by = bin.resolve("by")
        Files.writeString(by, "#!/bin/sh\n")
        Files.setPosixFilePermissions(by, PosixFilePermissions.fromString("rwxr-xr-x"))
        return bin
    }

    @AfterEach
    fun cleanUp() = onEdt {
        WriteAction.runAndWait<RuntimeException> {
            VfsUtil.markDirtyAndRefresh(false, true, true, baseDir())
            for (name in created) baseDir().findChild(name)?.delete(this)
        }
        created.clear()
        BasedPythonSettings.getInstance(project).loadState(BasedPythonSettings.State())
    }

    /**
     * Reported from the Marketplace: another plugin's toolbar widget asked for a repository in its
     * action `update`, where locks are prohibited, the repository's first `git worktree list` came
     * through here, and the read action taken here was reported as an error.
     */
    @Test
    fun `takes no lock where locks are prohibited`() {
        makeBasedPythonProject()
        val bin = venvWithBy()
        val root = baseDir()
        val envs = mutableMapOf("PATH" to "/usr/bin")
        val reported = mutableListOf<Throwable>()

        ApplicationManagerEx.getApplicationEx().withLocksSoftlyProhibited("ByVcsEnvCustomizerTest", { reported += it }) {
            ByVcsEnvCustomizer().customizeCommandAndEnvironment(
                project,
                envs,
                VcsEnvCustomizer.VcsExecutableContext(null, root, VcsEnvCustomizer.ExecutableType.LOCAL),
            )
        }

        assertEquals(emptyList<Throwable>(), reported)
        assertTrue(
            envs.getValue("PATH").split(File.pathSeparator).first() == bin.toString(),
            "the venv's bin first on PATH: ${envs["PATH"]}",
        )
    }
}
