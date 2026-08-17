package dev.basedpython.pycharm.format

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
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
 */
internal class BuffImportOptimizer : ImportOptimizer {

  override fun supports(file: PsiFile): Boolean =
    file.virtualFile?.fileType == BasedPythonFileType.INSTANCE &&
      BasedPythonSettings.getInstance(file.project).buffFormatting

  override fun processFile(file: PsiFile): Runnable {
    // Everything the background half needs, read while still on the EDT.
    val project = file.project
    val virtualFile = file.virtualFile
    val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }

    return Runnable {
      if (virtualFile == null || document == null) return@Runnable

      val server = ByCleanup.findServer(project, virtualFile) ?: run {
        LOG.debug("No running buff server for ${virtualFile.path} — imports left alone")
        return@Runnable
      }

      val edits = ByCleanup.requestEdits(server, virtualFile, ByCleanupOp.OptimizeImports)
      if (edits.isNullOrEmpty()) return@Runnable

      CommandProcessor.getInstance().runUndoTransparentAction {
        runWriteAction { ByCleanup.applyEditsTo(document, edits) }
      }
    }
  }
}
