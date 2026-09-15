package dev.basedpython.pycharm.format

import com.intellij.lang.SuspendableImportOptimizer
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings

private val LOG = Logger.getInstance(BuffImportOptimizer::class.java)

/**
 * *Optimize Imports* (Ctrl+Alt+O) for `.by` files.
 *
 * The contract the IDE attaches to this action is sort **and** remove unused — that is what
 * PyCharm's own Python implementation does. `source.organizeImports` is not that: it only sorts,
 * because sorting is all isort does, so an earlier version of this that ran
 * `buff check --fix --select I` quietly did half the job. [ByCleanupOp.OptimizeImports] is the pass
 * that does both.
 *
 * The work is asked of the running `buff` server rather than of a `buff` subprocess. A subprocess
 * rediscovers the project's configuration on every call and resolves it without the settings the
 * editor handed the server at startup, so the two can disagree about which rules apply — a file
 * tidied by one set and reported on by another.
 *
 * A [SuspendableImportOptimizer], because asking is the slow half and the platform runs the
 * [Runnable] this returns on the EDT inside a write action. The answer is fetched here, off the EDT
 * and outside any lock, where cancelling the platform's progress cancels the request; the runnable
 * only applies it.
 */
internal class BuffImportOptimizer(
  /** Where the running `buff` for a file is found; a parameter only so tests can hand in their own. */
  private val findServer: (Project, VirtualFile) -> LspClient? = ByCleanup::findServer,
) : SuspendableImportOptimizer {

  override fun supports(file: PsiFile): Boolean =
    file.virtualFile?.fileType == BasedPythonFileType.INSTANCE &&
      BasedPythonSettings.getInstance(file.project).buffFormatting

  override suspend fun processFileSuspend(file: PsiFile): Runnable {
    val project = file.project
    val (virtualFile, document) = readAction {
      val virtualFile = file.virtualFile
      virtualFile to virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
    }
    if (virtualFile == null || document == null) return EmptyRunnable.getInstance()

    val server = findServer(project, virtualFile) ?: run {
      LOG.debug("No running buff server for ${virtualFile.path} — imports left alone")
      return EmptyRunnable.getInstance()
    }

    val edits = ByCleanup.requestEdits(server, virtualFile, document, ByCleanupOp.OptimizeImports)
    if (edits == null || edits.isEmpty()) return EmptyRunnable.getInstance()

    // Already inside the platform's write command by the time this runs.
    return Runnable { ByCleanup.applyEditsTo(server, document, edits) }
  }
}
