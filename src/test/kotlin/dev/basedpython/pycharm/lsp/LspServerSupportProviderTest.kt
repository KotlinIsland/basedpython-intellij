package dev.basedpython.pycharm.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Binary-free guard-logic tests for [ByLspServerSupportProvider] and
 * [BuffLspServerSupportProvider].
 *
 * `fileOpened` is driven with a recording [LspServerStarter] fake that never starts a
 * real process — it just captures whether the provider *decided* to start a server.
 *
 * In CI no `by`/`buff` binary exists, so the "would start" path can't be asserted
 * positively without a real executable. Instead we assert the deterministic
 * early-return guards: wrong extension, and feature-disabled in settings. The
 * binary-missing branch is asserted to be graceful (no throw, no server started).
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class LspServerSupportProviderTest {

  private val fixture by codeInsightFixture()

  private val project get() = fixture.project

  /** Captures descriptors the provider asks to start. */
  private class RecordingStarter : LspServerStarter {
    val started = mutableListOf<LspServerDescriptor>()
    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
      started += descriptor
    }
  }

  private fun byFile(): VirtualFile = LightVirtualFile("module.by", "")
  private fun nonSourceFile(): VirtualFile = LightVirtualFile("notes.md", "")

  @AfterEach
  fun resetSettings() {
    val s = BasedPythonSettings.getInstance(project)
    s.byEnabled = true
    s.buffEnabled = true
    s.byPath = null
    s.buffPath = null
  }

  // ---------------------------------------------------------------------------
  // extension guard
  // ---------------------------------------------------------------------------

  @Test
  fun `by provider ignores non-source files`() {
    val starter = RecordingStarter()
    ByLspServerSupportProvider().fileOpened(project, nonSourceFile(), starter)
    assertTrue(starter.started.isEmpty(), "must not start a server for a .md file")
  }

  @Test
  fun `buff provider ignores non-source files`() {
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, nonSourceFile(), starter)
    assertTrue(starter.started.isEmpty(), "must not start a server for a .md file")
  }

  // ---------------------------------------------------------------------------
  // settings disabled guard
  // ---------------------------------------------------------------------------

  @Test
  fun `by provider does nothing when by is disabled`() {
    BasedPythonSettings.getInstance(project).byEnabled = false
    val starter = RecordingStarter()
    ByLspServerSupportProvider().fileOpened(project, byFile(), starter)
    assertTrue(starter.started.isEmpty(), "disabled `by` must not start a server")
  }

  @Test
  fun `buff provider does nothing when buff is disabled`() {
    BasedPythonSettings.getInstance(project).buffEnabled = false
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, byFile(), starter)
    assertTrue(starter.started.isEmpty(), "disabled `buff` must not start a server")
  }

  // ---------------------------------------------------------------------------
  // binary-missing branch is graceful
  // ---------------------------------------------------------------------------

  @Test
  fun `by provider handles a missing binary without throwing`() {
    val s = BasedPythonSettings.getInstance(project)
    s.byEnabled = true
    // Point at a path guaranteed not to exist so resolution returns null in CI.
    s.byPath = "/definitely/not/here/by"
    val starter = RecordingStarter()
    // Must not throw even though the binary cannot be resolved.
    ByLspServerSupportProvider().fileOpened(project, byFile(), starter)
    // With a bogus override and (in CI) nothing on PATH, no server should start.
    // On a dev box `by` could be on PATH; tolerate that by only asserting no crash.
    if (!BasedPythonBinaries.isByAvailable(project)) {
      assertTrue(starter.started.isEmpty(), "missing `by` binary must not start a server")
    }
  }

  @Test
  fun `buff provider handles a missing binary without throwing`() {
    val s = BasedPythonSettings.getInstance(project)
    s.buffEnabled = true
    s.buffPath = "/definitely/not/here/buff"
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, byFile(), starter)
    if (!BasedPythonBinaries.isBuffAvailable(project)) {
      assertTrue(starter.started.isEmpty(), "missing `buff` binary must not start a server")
    }
  }

  // ---------------------------------------------------------------------------
  // when a binary IS available, the provider starts the matching descriptor type
  // ---------------------------------------------------------------------------

  @Test
  fun `by provider starts a basedpython descriptor when a binary resolves`() {
    val s = BasedPythonSettings.getInstance(project)
    s.byEnabled = true
    val exe = makeFakeExecutable("by")
    s.byPath = exe
    val starter = RecordingStarter()
    ByLspServerSupportProvider().fileOpened(project, byFile(), starter)
    // The fake executable resolves, so exactly one `by` descriptor should be requested.
    assertEquals(1, starter.started.size)
    val desc = starter.started.single()
    assertTrue(desc is ByLspServerDescriptor)
    assertEquals("basedpython", desc.presentableName)
  }

  /** Writes a temp file marked executable; returns its absolute path. */
  private fun makeFakeExecutable(name: String): String {
    val f = java.io.File.createTempFile("fake-$name", "")
    f.deleteOnExit()
    f.setExecutable(true)
    return f.absolutePath
  }
}
