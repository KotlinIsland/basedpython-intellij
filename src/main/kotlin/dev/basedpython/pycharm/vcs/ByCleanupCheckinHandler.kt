package dev.basedpython.pycharm.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import org.eclipse.lsp4j.TextEdit
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.format.ByCleanupOp
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

private val LOG = Logger.getInstance(ByCleanupCheckinHandler::class.java)

internal class ByCleanupCheckinHandlerFactory : CheckinHandlerFactory() {
  override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
    ByCleanupCheckinHandler(panel)
}

/**
 * Applies the project's lint fixes to the files being committed.
 *
 * Reformatting and import tidying are not here: the commit dialog's own *Reformat code* and
 * *Optimize imports* options already cover those, and reach `buff` for the files this plugin owns.
 *
 * Only files the `buff` server already has open are touched, and that is a real limit rather than
 * an oversight. The pass is answered from the server's copy of a document, which it has only for a
 * file the editor opened or that has unsaved changes — the platform decides what to hand it and
 * offers no way to push a file in. A commit whose files are all closed is therefore left alone,
 * which is quiet rather than wrong; running them through a `buff` subprocess instead would resolve
 * the project's configuration by a different route than the editor does, and the two disagreeing
 * about which rules apply is worse than not tidying.
 *
 * Off by default. It rewrites files *after* the diff has been reviewed, which the platform's own
 * reformat-on-commit does too, but it should be asked for rather than assumed.
 */
internal class ByCleanupCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler() {

  private val project: Project get() = panel.project
  private val settings get() = BasedPythonSettings.getInstance(project)

  override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent = CleanupOptions()

  override fun beforeCheckin(): ReturnResult {
    if (!settings.fixAllOnCommit) return ReturnResult.COMMIT

    // No file-type test: which files the fixes apply to is the formatter/linter server's own
    // answer, and `findServer` below is where it is asked. That covers `.py` and `.pyi` as well as
    // `.by` and `.byi`.
    val files = panel.virtualFiles
    if (files.isEmpty()) return ReturnResult.COMMIT

    ProgressManager.getInstance().runProcessWithProgressSynchronously(
      { runCleanup(files) },
      BasedPythonBundle.message("progress.cleanupOnCommit"),
      true,
      project,
    )

    return ReturnResult.COMMIT
  }

  private fun runCleanup(files: Collection<VirtualFile>) {
    val indicator = ProgressManager.getInstance().progressIndicator
    val documents = FileDocumentManager.getInstance()

    val changed = mutableListOf<VirtualFile>()
    for (file in files) {
      indicator?.checkCanceled()
      indicator?.text2 = file.name

      val server = ByCleanup.findServer(project, file) ?: continue
      val document = documents.getDocument(file) ?: continue

      val edits = ByCleanup.requestEdits(server, file, ByCleanupOp.FixAll)
      if (edits.isNullOrEmpty()) continue

      // Asking the server happens here, on the progress thread; the write has to go back to the EDT.
      applyOnEdt(document, edits)
      changed += file
    }

    if (changed.isNotEmpty()) {
      // The committed content is read from disk, so what was just rewritten in memory has to reach
      // it before the commit does.
      applyOnEdt { documents.saveAllDocuments() }
      LOG.debug("cleanup rewrote ${changed.size} file(s) before commit")
    }
  }

  private fun applyOnEdt(document: Document, edits: List<TextEdit>) {
    // A document may only be changed inside a command, not merely inside a write action.
    applyOnEdt {
      CommandProcessor.getInstance().executeCommand(
        project,
        { runWriteAction { ByCleanup.applyEditsTo(document, edits) } },
        BasedPythonBundle.message("progress.cleanupOnCommit"),
        null,
      )
    }
  }

  private fun applyOnEdt(action: () -> Unit) {
    ApplicationManager.getApplication()
      .invokeAndWait(action, ModalityState.defaultModalityState())
  }

  /** The checkbox under *Before Commit*, beside the platform's own reformat and optimize entries. */
  private inner class CleanupOptions : RefreshableOnComponent {
    private val fixAll = JBCheckBox(BasedPythonBundle.message("commit.fixAllName"))

    override fun getComponent(): JComponent = panel {
      row { cell(fixAll) }
    }

    override fun saveState() {
      settings.fixAllOnCommit = fixAll.isSelected
    }

    override fun restoreState() {
      fixAll.isSelected = settings.fixAllOnCommit
    }
  }
}
