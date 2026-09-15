package dev.basedpython.pycharm.vcs

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.committedVirtualFiles
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.platform.util.progress.withProgressText
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.format.ByCleanupOp
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * A [CommitCheck] rather than `beforeCheckin`: the check suspends while `buff` answers instead of
 * holding a modal progress thread, runs in the non-modal commit flow, and is cancelled with the
 * commit. It runs in the [CommitCheck.ExecutionOrder.MODIFICATION] phase beside the platform's own
 * reformat and optimize-imports checks.
 *
 * Off by default. It rewrites files *after* the diff has been reviewed, which the platform's own
 * reformat-on-commit does too, but it should be asked for rather than assumed.
 */
internal class ByCleanupCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler(), CommitCheck {

  private val project: Project get() = panel.project
  private val settings get() = BasedPythonSettings.getInstance(project)

  override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent = CleanupOptions()

  override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.MODIFICATION

  override fun isEnabled(): Boolean = settings.fixAllOnCommit

  override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
    // No file-type test: which files the fixes apply to is the formatter/linter server's own
    // answer, and `findServer` below is where it is asked. That covers `.py` and `.pyi` as well as
    // `.by` and `.byi`.
    val files = commitInfo.committedVirtualFiles
    if (files.isEmpty()) return null

    withContext(Dispatchers.Default) {
      withProgressText(BasedPythonBundle.message("progress.cleanupOnCommit")) {
        var changed = 0
        for (file in files) {
          if (ByCleanup.findServer(project, file) == null) continue
          val document = readAction { FileDocumentManager.getInstance().getDocument(file) } ?: continue
          if (!ByCleanup.run(project, file, document, ByCleanupOp.FixAll)) continue

          // What is committed is read from disk, so the rewrite has to reach it first. Only this
          // document is saved: the commit is no licence to write out unrelated unsaved edits.
          withContext(Dispatchers.EDT) {
            writeIntentReadAction { FileDocumentManager.getInstance().saveDocument(document) }
          }
          changed++
        }
        LOG.debug("cleanup rewrote $changed file(s) before commit")
      }
    }
    return null
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
