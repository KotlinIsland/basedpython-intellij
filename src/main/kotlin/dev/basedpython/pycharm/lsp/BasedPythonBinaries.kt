package dev.basedpython.pycharm.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Path

/**
 * Resolves the `by` (type-checker LSP) and `buff` (formatter LSP) binaries.
 *
 * A thin, binary-naming layer over [ByEnvironments], which owns the resolution order, venv
 * activation, and uv support. See [ByEnvironments.resolve] for the order.
 *
 * The path override comes from [BasedPythonSettings.effectiveByPath] / [BasedPythonSettings.effectiveBuffPath],
 * so an IDE-wide default set on the "basedpython Defaults" page applies when the project leaves
 * its own path blank.
 *
 * Returns `null` when nothing is found — callers must not start a process in that case.
 */
object BasedPythonBinaries {

  const val BIN_BY: String = "by"
  const val BIN_BUFF: String = "buff"

  /** Full launch for `by`: executable, any argument prefix (uv), and the environment to run in. */
  fun launchBy(
    project: Project,
    contextFile: VirtualFile? = null,
    kind: ByEnvironmentKind = ByEnvironmentKind.AUTO,
  ): ByLaunch? = ByEnvironments.resolve(
    project, BIN_BY, contextFile, kind, BasedPythonSettings.getInstance(project).effectiveByPath,
  )

  /** Full launch for `buff`. */
  fun launchBuff(
    project: Project,
    contextFile: VirtualFile? = null,
    kind: ByEnvironmentKind = ByEnvironmentKind.AUTO,
  ): ByLaunch? = ByEnvironments.resolve(
    project, BIN_BUFF, contextFile, kind, BasedPythonSettings.getInstance(project).effectiveBuffPath,
  )

  /** True when `by` can be located. For availability checks (banners, inspections, gating). */
  fun isByAvailable(project: Project, contextFile: VirtualFile? = null): Boolean =
    launchBy(project, contextFile) != null

  /** True when `buff` can be located. */
  fun isBuffAvailable(project: Project, contextFile: VirtualFile? = null): Boolean =
    launchBuff(project, contextFile) != null

  /**
   * The `by` executable path — for display and tests only.
   *
   * There is deliberately no "just give me the path" API for callers that execute. The executable
   * alone is not enough to run the toolchain: a uv-backed launch execs `uv` and names `by` in
   * [ByLaunch.prependArgs], so appending arguments to the bare path would invoke a different tool.
   * Anything that starts a process must use [launchBy] and honour the prefix and the environment.
   */
  fun resolveByExe(project: Project, contextFile: VirtualFile? = null): Path? = launchBy(project, contextFile)?.exe

  /** The `buff` executable path. See [resolveByExe] — display and tests only. */
  fun resolveBuffExe(project: Project, contextFile: VirtualFile? = null): Path? =
    launchBuff(project, contextFile)?.exe

  /** Kept as the resolution-order entry point for tests; delegates to [ByEnvironments]. */
  fun searchStartDirs(contentRoot: Path?, projectBase: Path?): List<Path> =
    ByEnvironments.searchStartDirs(contentRoot, projectBase)
}
