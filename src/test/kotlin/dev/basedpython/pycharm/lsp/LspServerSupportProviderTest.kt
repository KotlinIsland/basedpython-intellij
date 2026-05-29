package dev.basedpython.pycharm.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.settings.BasedPythonSettings

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
class LspServerSupportProviderTest : BasePlatformTestCase() {

  /** Captures descriptors the provider asks to start. */
  private class RecordingStarter : LspServerStarter {
    val started = mutableListOf<LspServerDescriptor>()
    override fun ensureServerStarted(descriptor: LspServerDescriptor) {
      started += descriptor
    }
  }

  private fun byFile(): VirtualFile = LightVirtualFile("module.by", "")
  private fun nonSourceFile(): VirtualFile = LightVirtualFile("notes.md", "")

  override fun tearDown() {
    try {
      val s = BasedPythonSettings.getInstance(project)
      s.byEnabled = true
      s.buffEnabled = true
      s.byPath = null
      s.buffPath = null
    } finally {
      super.tearDown()
    }
  }

  // ---------------------------------------------------------------------------
  // extension guard
  // ---------------------------------------------------------------------------

  fun `test by provider ignores non-source files`() {
    val starter = RecordingStarter()
    ByLspServerSupportProvider().fileOpened(project, nonSourceFile(), starter)
    assertTrue("must not start a server for a .md file", starter.started.isEmpty())
  }

  fun `test buff provider ignores non-source files`() {
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, nonSourceFile(), starter)
    assertTrue("must not start a server for a .md file", starter.started.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // settings disabled guard
  // ---------------------------------------------------------------------------

  fun `test by provider does nothing when by is disabled`() {
    BasedPythonSettings.getInstance(project).byEnabled = false
    val starter = RecordingStarter()
    ByLspServerSupportProvider().fileOpened(project, byFile(), starter)
    assertTrue("disabled `by` must not start a server", starter.started.isEmpty())
  }

  fun `test buff provider does nothing when buff is disabled`() {
    BasedPythonSettings.getInstance(project).buffEnabled = false
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, byFile(), starter)
    assertTrue("disabled `buff` must not start a server", starter.started.isEmpty())
  }

  // ---------------------------------------------------------------------------
  // binary-missing branch is graceful
  // ---------------------------------------------------------------------------

  fun `test by provider handles a missing binary without throwing`() {
    val s = BasedPythonSettings.getInstance(project)
    s.byEnabled = true
    // Point at a path guaranteed not to exist so resolution returns null in CI.
    s.byPath = "/definitely/not/here/by"
    val starter = RecordingStarter()
    // Must not throw even though the binary cannot be resolved.
    ByLspServerSupportProvider().fileOpened(project, byFile(), starter)
    // With a bogus override and (in CI) nothing on PATH, no server should start.
    // On a dev box `by` could be on PATH; tolerate that by only asserting no crash.
    if (BasedPythonBinaries.resolveBy(project) == null) {
      assertTrue("missing `by` binary must not start a server", starter.started.isEmpty())
    }
  }

  fun `test buff provider handles a missing binary without throwing`() {
    val s = BasedPythonSettings.getInstance(project)
    s.buffEnabled = true
    s.buffPath = "/definitely/not/here/buff"
    val starter = RecordingStarter()
    BuffLspServerSupportProvider().fileOpened(project, byFile(), starter)
    if (BasedPythonBinaries.resolveBuff(project) == null) {
      assertTrue("missing `buff` binary must not start a server", starter.started.isEmpty())
    }
  }

  // ---------------------------------------------------------------------------
  // when a binary IS available, the provider starts the matching descriptor type
  // ---------------------------------------------------------------------------

  fun `test by provider starts a basedpython descriptor when a binary resolves`() {
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
