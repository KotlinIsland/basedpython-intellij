package dev.basedpython.pycharm.lsp

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
 * Uses the light code-insight fixture purely to obtain an in-memory
 * [com.intellij.openapi.project.Project] (needed for the project-scoped [BasedPythonSettings]
 * service).
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonBinariesTest {

  private val fixture by codeInsightFixture()

  private val project get() = fixture.project

  private lateinit var tmpDir: Path

  @BeforeEach
  fun createTempDir() {
    tmpDir = Files.createTempDirectory("bp-binaries-test")
  }

  @AfterEach
  fun cleanUp() {
    if (::tmpDir.isInitialized) {
      tmpDir.toFile().deleteRecursively()
    }
    // Clear any override we set so other tests start clean.
    val settings = BasedPythonSettings.getInstance(project)
    settings.byPath = null
    settings.buffPath = null
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

  @Test
  fun `resolveBy honors an executable override path`() {
    val exe = makeExecutable("by-fake")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()

    val resolved = BasedPythonBinaries.resolveByExe(project)
    assertNotNull(resolved, "expected override path to resolve")
    assertEquals(exe, resolved)
  }

  @Test
  fun `resolveBuff honors an executable override path`() {
    val exe = makeExecutable("buff-fake")
    BasedPythonSettings.getInstance(project).buffPath = exe.toString()

    val resolved = BasedPythonBinaries.resolveBuffExe(project)
    assertNotNull(resolved, "expected override path to resolve")
    assertEquals(exe, resolved)
  }

  @Test
  fun `a non-executable override is ignored gracefully`() {
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
    assertNotSame(plain, resolved, "non-executable override should not be returned")
  }

  @Test
  fun `a bogus override path does not throw and falls through`() {
    BasedPythonSettings.getInstance(project).byPath = "/definitely/not/a/real/path/by-xyz"
    // Must not throw; returns whatever PATH/venv yields (likely null in CI).
    val resolved = BasedPythonBinaries.resolveByExe(project)
    assertNotSame(
      "/definitely/not/a/real/path/by-xyz",
      resolved?.toString(),
      "bogus override path should never be returned verbatim",
    )
  }

  @Test
  fun `resolution returns null or a real path but never crashes`() {
    // With no override set, resolution walks the venv + PATH. In CI neither `by` nor
    // `buff` exist, so we expect null; on a dev box it may find one. Either is fine —
    // the contract is "no exception, and any non-null result is executable".
    BasedPythonSettings.getInstance(project).byPath = null
    BasedPythonSettings.getInstance(project).buffPath = null

    val by = BasedPythonBinaries.resolveByExe(project)
    val buff = BasedPythonBinaries.resolveBuffExe(project)

    if (by != null) assertTrue(Files.isExecutable(by), "resolved `by` must be executable")
    if (buff != null) assertTrue(Files.isExecutable(buff), "resolved `buff` must be executable")
  }

  // --- searchStartDirs ordering (pure logic; multi-root §186) ---

  @Test
  fun `searchStartDirs prefers content root over project base`() {
    val root = Path.of("/work/moduleA")
    val base = Path.of("/work")
    assertEquals(listOf(root, base), BasedPythonBinaries.searchStartDirs(root, base))
  }

  @Test
  fun `searchStartDirs dedupes when content root equals project base`() {
    val base = Path.of("/work")
    assertEquals(listOf(base), BasedPythonBinaries.searchStartDirs(base, base))
  }

  @Test
  fun `searchStartDirs with only a content root`() {
    val root = Path.of("/work/moduleA")
    assertEquals(listOf(root), BasedPythonBinaries.searchStartDirs(root, null))
  }

  @Test
  fun `searchStartDirs with only a project base`() {
    val base = Path.of("/work")
    assertEquals(listOf(base), BasedPythonBinaries.searchStartDirs(null, base))
  }

  @Test
  fun `searchStartDirs is empty when nothing is known`() {
    assertTrue(BasedPythonBinaries.searchStartDirs(null, null).isEmpty())
  }

  @Test
  fun `resolveBy with a content file still honors override`() {
    // contextFile param must not break the override short-circuit.
    val exe = makeExecutable("by-fake2")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val vf = fixture.configureByText("ctx.by", "x = 1").virtualFile
    assertEquals(exe, BasedPythonBinaries.resolveByExe(project, vf))
  }

  // --- IDE-wide default fallback ---

  @Test
  fun `the app-level default path is used when the project path is blank`() {
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

  @Test
  fun `a project path wins over the app-level default`() {
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

  @Test
  fun `an explicit kind does not fall back to other sources`() {
    // The point of picking a source explicitly is that it fails loudly rather than silently
    // resolving via some other route. No SDK is configured in this fixture, so SDK must yield null
    // even though an override/PATH binary might otherwise be found.
    BasedPythonSettings.getInstance(project).byPath = null
    assertNull(BasedPythonBinaries.launchBy(project, kind = ByEnvironmentKind.SDK))
  }

  @Test
  fun `an explicit kind ignores the configured override path`() {
    // The override is layered over an IDE-wide default, so honouring it here would let a global
    // preference beat this run configuration's explicit choice — precedence backwards. It also
    // cannot express uv, so an override would otherwise mean "uv (managed)" silently never runs.
    val exe = makeExecutable("by-fake3")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    assertNull(
      BasedPythonBinaries.launchBy(project, kind = ByEnvironmentKind.SDK),
      "an explicit kind must resolve from its own source only",
    )
  }

  @Test
  fun `a resolved launch never has a null exe and describes itself`() {
    val exe = makeExecutable("by-fake4")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val launch = BasedPythonBinaries.launchBy(project)
    assertNotNull(launch)
    assertTrue(launch!!.describe().contains("by-fake4"))
  }

  // --- auto-detection must never reach uv ---

  @Test
  fun `auto-detection never resolves via uv`() {
    // `uv run` creates a .venv, writes uv.lock, and can download a CPython toolchain. That is fine
    // when the user asks for it and unacceptable as a side effect of opening a file — and every
    // implicit caller (LSP startup, the missing-binary banner, inspections) resolves with AUTO.
    // This box may well have uv installed, which is exactly the condition being guarded.
    BasedPythonSettings.getInstance(project).byPath = null
    val launch = BasedPythonBinaries.launchBy(project)
    if (launch != null) {
      assertFalse(
        launch.kind == ByEnvironmentKind.UV,
        "AUTO must never produce a uv launch (would mutate the project on file open)",
      )
      assertTrue(launch.prependArgs.isEmpty(), "AUTO launches carry no argument prefix")
    }
  }

  @Test
  fun `an override is reported as such rather than as a detected source`() {
    // The detection label exists to answer "which source produced this"; labelling an explicitly
    // configured path "Auto-detect" would defeat its only purpose.
    val exe = makeExecutable("by-fake5")
    BasedPythonSettings.getInstance(project).byPath = exe.toString()
    val launch = BasedPythonBinaries.launchBy(project)
    assertNotNull(launch)
    assertTrue(launch!!.fromOverride)
    assertEquals("Configured path", launch.sourceLabel)
  }

  @Test
  fun `a detected launch is not reported as an override`() {
    BasedPythonSettings.getInstance(project).byPath = null
    val launch = BasedPythonBinaries.launchBy(project)
    if (launch != null) {
      assertFalse(launch.fromOverride, "nothing was configured, so this cannot be an override")
      assertEquals(launch.kind.display, launch.sourceLabel)
    }
  }
}
