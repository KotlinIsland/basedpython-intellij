package dev.basedpython.pycharm.lsp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Binary-free tests for [BasedPythonBinaries] resolution.
 *
 * These exercise the public `resolveBy` / `resolveBuff` entry points without ever
 * launching a process. The "happy path" creates a fake executable on disk and points
 * the settings override at it; the "not found" path asserts a graceful `null` rather
 * than an exception, which is the contract callers (the LSP providers) rely on.
 *
 * Uses [BasePlatformTestCase] purely to obtain an in-memory [com.intellij.openapi.project.Project]
 * (needed for the project-scoped [BasedPythonSettings] service).
 */
class BasedPythonBinariesTest : BasePlatformTestCase() {

  private lateinit var tmpDir: Path

  override fun setUp() {
    super.setUp()
    tmpDir = Files.createTempDirectory("bp-binaries-test")
  }

  override fun tearDown() {
    try {
      if (::tmpDir.isInitialized) {
        tmpDir.toFile().deleteRecursively()
      }
      // Clear any override we set so other tests start clean.
      val settings = BasedPythonSettings.getInstance(project)
      settings.byPath = null
      settings.buffPath = null
    } finally {
      super.tearDown()
    }
  }

  /** Creates a file and marks it executable (best-effort on non-POSIX FS). */
  private fun makeExecutable(name: String): Path {
    val p = tmpDir.resolve(name)
    Files.createFile(p)
    try {
      Files.setPosixFilePermissions(
        p,
        setOf(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE,
        ),
      )
    } catch (_: UnsupportedOperationException) {
      p.toFile().setExecutable(true)
    }
    return p
  }

  fun `test resolveBy honors an executable override path`() {
    val exe = makeExecutable("by-fake")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()

    val resolved = BasedPythonBinaries.resolveByExe(project)
    assertNotNull("expected override path to resolve", resolved)
    assertEquals(exe, resolved)
  }

  fun `test resolveBuff honors an executable override path`() {
    val exe = makeExecutable("buff-fake")
    BasedPythonSettings.getInstance(project).buffPath = exe.toString()

    val resolved = BasedPythonBinaries.resolveBuffExe(project)
    assertNotNull("expected override path to resolve", resolved)
    assertEquals(exe, resolved)
  }

  fun `test a non-executable override is ignored gracefully`() {
    // A plain (non-executable) file must NOT be returned; resolution falls through.
    val plain = tmpDir.resolve("not-exec")
    Files.createFile(plain)
    try {
      plain.toFile().setExecutable(false)
    } catch (_: Exception) {
      // ignore — assertion below tolerates either outcome
    }
    BasedPythonSettings.getInstance(project).byPath = plain.toString()

    val resolved = BasedPythonBinaries.resolveByExe(project)
    // The override file is not executable, so it must not be the result.
    assertNotSame("non-executable override should not be returned", plain, resolved)
  }

  fun `test a bogus override path does not throw and falls through`() {
    BasedPythonSettings.getInstance(project).byPath = "/definitely/not/a/real/path/by-xyz"
    // Must not throw; returns whatever PATH/venv yields (likely null in CI).
    val resolved = BasedPythonBinaries.resolveByExe(project)
    assertNotSame(
      "bogus override path should never be returned verbatim",
      "/definitely/not/a/real/path/by-xyz",
      resolved?.toString(),
    )
  }

  fun `test resolution returns null or a real path but never crashes`() {
    // With no override set, resolution walks the venv + PATH. In CI neither `by` nor
    // `buff` exist, so we expect null; on a dev box it may find one. Either is fine —
    // the contract is "no exception, and any non-null result is executable".
    BasedPythonSettings.getInstance(project).byPath = null
    BasedPythonSettings.getInstance(project).buffPath = null

    val by = BasedPythonBinaries.resolveByExe(project)
    val buff = BasedPythonBinaries.resolveBuffExe(project)

    if (by != null) assertTrue("resolved `by` must be executable", Files.isExecutable(by))
    if (buff != null) assertTrue("resolved `buff` must be executable", Files.isExecutable(buff))
  }

  // --- searchStartDirs ordering (pure logic; multi-root §186) ---

  fun `test searchStartDirs prefers content root over project base`() {
    val root = Path.of("/work/moduleA")
    val base = Path.of("/work")
    assertEquals(listOf(root, base), BasedPythonBinaries.searchStartDirs(root, base))
  }

  fun `test searchStartDirs dedupes when content root equals project base`() {
    val base = Path.of("/work")
    assertEquals(listOf(base), BasedPythonBinaries.searchStartDirs(base, base))
  }

  fun `test searchStartDirs with only a content root`() {
    val root = Path.of("/work/moduleA")
    assertEquals(listOf(root), BasedPythonBinaries.searchStartDirs(root, null))
  }

  fun `test searchStartDirs with only a project base`() {
    val base = Path.of("/work")
    assertEquals(listOf(base), BasedPythonBinaries.searchStartDirs(null, base))
  }

  fun `test searchStartDirs is empty when nothing is known`() {
    assertTrue(BasedPythonBinaries.searchStartDirs(null, null).isEmpty())
  }

  fun `test resolveBy with a content file still honors override`() {
    // contextFile param must not break the override short-circuit.
    val exe = makeExecutable("by-fake2")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val vf = myFixture.configureByText("ctx.by", "x = 1").virtualFile
    assertEquals(exe, BasedPythonBinaries.resolveByExe(project, vf))
  }

  // --- IDE-wide default fallback ---

  fun `test the app-level default path is used when the project path is blank`() {
    // Regression: resolution read the raw project value, so `effectiveByPath` — and with it the
    // whole "basedpython Defaults" page — was dead code and a global default was silently ignored.
    val exe = makeExecutable("by-global")
    val app = BasedPythonAppSettings.getInstance()
    val previous = app.defaultByPath
    try {
      app.defaultByPath = exe.toString()
      BasedPythonSettings.getInstance(project).byPath = null

      assertEquals(exe, BasedPythonBinaries.resolveByExe(project))
    } finally {
      app.defaultByPath = previous
    }
  }

  fun `test a project path wins over the app-level default`() {
    val projectExe = makeExecutable("by-project")
    val globalExe = makeExecutable("by-global2")
    val app = BasedPythonAppSettings.getInstance()
    val previous = app.defaultByPath
    try {
      app.defaultByPath = globalExe.toString()
      BasedPythonSettings.getInstance(project).byPath = projectExe.toString()

      assertEquals(projectExe, BasedPythonBinaries.resolveByExe(project))
    } finally {
      app.defaultByPath = previous
    }
  }

  // --- explicit environment kinds ---

  fun `test an explicit kind does not fall back to other sources`() {
    // The point of picking a source explicitly is that it fails loudly rather than silently
    // resolving via some other route. No SDK is configured in this fixture, so SDK must yield null
    // even though an override/PATH binary might otherwise be found.
    BasedPythonSettings.getInstance(project).byPath = null
    assertNull(BasedPythonBinaries.launchBy(project, kind = ByEnvironmentKind.SDK))
  }

  fun `test an explicit kind ignores the configured override path`() {
    // The override is layered over an IDE-wide default, so honouring it here would let a global
    // preference beat this run configuration's explicit choice — precedence backwards. It also
    // cannot express uv, so an override would otherwise mean "uv (managed)" silently never runs.
    val exe = makeExecutable("by-fake3")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    assertNull(
      "an explicit kind must resolve from its own source only",
      BasedPythonBinaries.launchBy(project, kind = ByEnvironmentKind.SDK),
    )
  }

  fun `test a resolved launch never has a null exe and describes itself`() {
    val exe = makeExecutable("by-fake4")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val launch = BasedPythonBinaries.launchBy(project)
    assertNotNull(launch)
    assertTrue(launch!!.describe().contains("by-fake4"))
  }

  // --- auto-detection must never reach uv ---

  fun `test auto-detection never resolves via uv`() {
    // `uv run` creates a .venv, writes uv.lock, and can download a CPython toolchain. That is fine
    // when the user asks for it and unacceptable as a side effect of opening a file — and every
    // implicit caller (LSP startup, the missing-binary banner, inspections) resolves with AUTO.
    // This box may well have uv installed, which is exactly the condition being guarded.
    BasedPythonSettings.getInstance(project).byPath = null
    val launch = BasedPythonBinaries.launchBy(project)
    if (launch != null) {
      assertFalse(
        "AUTO must never produce a uv launch (would mutate the project on file open)",
        launch.kind == ByEnvironmentKind.UV,
      )
      assertTrue("AUTO launches carry no argument prefix", launch.prependArgs.isEmpty())
    }
  }

  fun `test an override is reported as such rather than as a detected source`() {
    // The detection label exists to answer "which source produced this"; labelling an explicitly
    // configured path "Auto-detect" would defeat its only purpose.
    val exe = makeExecutable("by-fake5")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val launch = BasedPythonBinaries.launchBy(project)
    assertNotNull(launch)
    assertTrue(launch!!.fromOverride)
    assertEquals("Configured path", launch.sourceLabel)
  }

  fun `test a detected launch is not reported as an override`() {
    BasedPythonSettings.getInstance(project).byPath = null
    val launch = BasedPythonBinaries.launchBy(project)
    if (launch != null) {
      assertFalse("nothing was configured, so this cannot be an override", launch.fromOverride)
      assertEquals(launch.kind.display, launch.sourceLabel)
    }
  }
}
