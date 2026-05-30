package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the `by` (type-checker LSP) and `buff` (formatter LSP) binaries.
 *
 * Resolution order:
 *   1. User-supplied override path from [BasedPythonSettings] (if non-blank and exists).
 *   2. Walk up (max 5 dirs) from the search roots looking for `.venv/bin/<name>`
 *      (or `.venv/Scripts/<name>.exe` on Windows). In a multi-root / multi-module
 *      workspace the file's own content root is searched first, then `project.basePath`,
 *      so a per-module `.venv` wins over a workspace-level one.
 *   3. `PATH` lookup via [PathEnvironmentVariableUtil].
 *
 * Returns `null` when nothing is found — callers must not start the LSP in that case.
 */
object BasedPythonBinaries {
  private val LOG = Logger.getInstance(BasedPythonBinaries::class.java)
  private const val MAX_WALK_UP = 5

  fun resolveBy(project: Project, contextFile: VirtualFile? = null): Path? =
    resolve(project, contextFile, BIN_BY, BasedPythonSettings.getInstance(project).byPath)

  fun resolveBuff(project: Project, contextFile: VirtualFile? = null): Path? =
    resolve(project, contextFile, BIN_BUFF, BasedPythonSettings.getInstance(project).buffPath)

  private fun resolve(project: Project, contextFile: VirtualFile?, binary: String, override: String?): Path? {
    if (!override.isNullOrBlank()) {
      val p = Paths.get(override)
      if (Files.isExecutable(p)) return p
      LOG.warn("Configured $binary override path does not exist or is not executable: $override")
    }

    for (start in searchStartDirs(contentRootPath(project, contextFile), project.basePath?.let { Paths.get(it) })) {
      var dir: Path? = start
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

  /**
   * Ordered, de-duplicated list of directories to begin the venv walk-up from.
   * The file's content root takes precedence over the project base so a per-module
   * `.venv` is preferred. Pure function — unit tested.
   */
  fun searchStartDirs(contentRoot: Path?, projectBase: Path?): List<Path> =
    listOfNotNull(contentRoot, projectBase).distinct()

  /** The content root (as an NIO [Path]) of [file] within [project], or `null`. */
  private fun contentRootPath(project: Project, file: VirtualFile?): Path? {
    if (file == null || project.isDefault) return null
    val index = ProjectFileIndex.getInstance(project)
    val root = index.getContentRootForFile(file) ?: index.getSourceRootForFile(file) ?: return null
    return runCatching { root.toNioPath() }.getOrNull()
  }

  private fun venvBinary(root: Path, binary: String): Path =
    if (SystemInfo.isWindows) root.resolve(".venv").resolve("Scripts").resolve("$binary.exe")
    else root.resolve(".venv").resolve("bin").resolve(binary)

  private const val BIN_BY = "by"
  private const val BIN_BUFF = "buff"
}
