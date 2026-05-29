package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the `by` (type-checker LSP) and `buff` (formatter LSP) binaries.
 *
 * Resolution order:
 *   1. User-supplied override path from [BasedPythonSettings] (if non-blank and exists).
 *   2. Walk up from `project.basePath` (max 5 dirs) looking for `.venv/bin/<name>`
 *      (or `.venv/Scripts/<name>.exe` on Windows).
 *   3. `PATH` lookup via [PathEnvironmentVariableUtil].
 *
 * Returns `null` when nothing is found — callers must not start the LSP in that case.
 */
object BasedPythonBinaries {
  private val LOG = Logger.getInstance(BasedPythonBinaries::class.java)
  private const val MAX_WALK_UP = 5

  fun resolveBy(project: Project): Path? = resolve(project, BIN_BY, BasedPythonSettings.getInstance(project).byPath)
  fun resolveBuff(project: Project): Path? = resolve(project, BIN_BUFF, BasedPythonSettings.getInstance(project).buffPath)

  private fun resolve(project: Project, binary: String, override: String?): Path? {
    if (!override.isNullOrBlank()) {
      val p = Paths.get(override)
      if (Files.isExecutable(p)) return p
      LOG.warn("Configured $binary override path does not exist or is not executable: $override")
    }

    val base = project.basePath?.let { Paths.get(it) }
    if (base != null) {
      var dir: Path? = base
      var hops = 0
      while (dir != null && hops <= MAX_WALK_UP) {
        val candidate = venvBinary(dir, binary)
        if (Files.isExecutable(candidate)) return candidate
        dir = dir.parent
        hops++
      }
    }

    val onPath = PathEnvironmentVariableUtil.findInPath(binary)
    if (onPath != null) return onPath.toPath()

    return null
  }

  private fun venvBinary(root: Path, binary: String): Path =
    if (SystemInfo.isWindows) root.resolve(".venv").resolve("Scripts").resolve("$binary.exe")
    else root.resolve(".venv").resolve("bin").resolve(binary)

  private const val BIN_BY = "by"
  private const val BIN_BUFF = "buff"
}
