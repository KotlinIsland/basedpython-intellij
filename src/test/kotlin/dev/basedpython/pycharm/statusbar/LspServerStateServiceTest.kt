package dev.basedpython.pycharm.statusbar

import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * What the status bar widget reads on every paint, which is on the EDT and so must not be where the
 * binaries are looked for.
 */
@TestFixtures
class LspServerStateServiceTest {

    private val fixture by codeInsightFixture()

    private val temp: Path = Files.createTempDirectory("status-bar-test")

    @AfterEach
    fun cleanUp() = onEdt {
        BasedPythonSettings.getInstance(fixture.project).byPath = null
        temp.toFile().deleteRecursively()
    }

    private fun executable(name: String): Path {
        val exe = temp.resolve(name)
        Files.writeString(exe, "#!/bin/sh\n")
        Files.setPosixFilePermissions(exe, PosixFilePermission.entries.toSet())
        return exe
    }

    @Test
    fun `a snapshot reports what the last background look found, not what is on disk now`() = onEdt {
        // A fresh instance, so no earlier test has already looked.
        val service = LspServerStateService(fixture.project)
        try {
            val exe = executable("by")
            BasedPythonSettings.getInstance(fixture.project).byPath = exe.toString()

            val before = service.snapshot()
            assertEquals(false, before.resolved)
            assertNull(before.byPath, "the snapshot looked for the binary itself")
            // Not looked for is not missing: whatever the light says, it is not a missing binary. The
            // light project is shared with the rest of the suite, so the server state is whatever
            // it was left in, and the expectation is built from that.
            val state = LspClientManager.getInstance(fixture.project)
                .getClients(ByLspServerSupportProvider::class.java).firstOrNull()?.state
            assertEquals(ServerLightMapping.lightFor(true, binaryMissing = false, state = state), before.byLight)

            var done = false
            service.refresh { done = true }
            PlatformTestUtil.waitWithEventsDispatching("the background look never finished", { done }, 10)

            val after = service.snapshot()
            assertEquals(true, after.resolved)
            assertEquals(exe.toString(), after.byPath)
            // No server has started in a light test, so there is nothing to report a version for.
            assertNull(after.byVersion)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(service)
        }
    }
}
