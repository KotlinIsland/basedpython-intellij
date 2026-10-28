package dev.basedpython.pycharm.vcs.git

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.util.progress.withProgressText
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.apilock.ByApiLockScope
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByReconcileMergeImportsParams
import dev.basedpython.pycharm.lsp.ext.ByReconciledImports
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import dev.basedpython.pycharm.vcs.ByMergeImports
import dev.basedpython.pycharm.vcs.ByMergeVersions
import git4idea.GitUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException

private val LOG = Logger.getInstance(ByMergeImportsCheckinHandler::class.java)

internal class ByMergeImportsCheckinHandlerFactory : CheckinHandlerFactory() {
  override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
    ByMergeImportsCheckinHandler(panel)
}

/**
 * Offers, on the commit that ends a merge, a rebase step or a cherry-pick, to reconcile the imports of
 * each python file the operation merged from two changed sides.
 *
 * A line merge can be clean and still break a file: ours removes the last function reading `json` and
 * `import json` with it, theirs adds another, and git writes a file that reads `json` and imports
 * nothing. The platform offers no hook on a merge's whole result, and a file git merged cleanly never
 * reaches the merge window at all, but until the operation is committed git still names all three
 * versions ([ByGitMerges]), and the commit is where the merged files are. `by/reconcileMergeImports`
 * says what the imports should be, from the versions and the file as it is now — the merge window's
 * resolutions and any edit since included.
 *
 * A merge git commits by itself — one with no conflict, run without `--no-commit` — makes no commit
 * here, so nothing is checked: that is what `by merge-driver` is for. A rebase continued with *Continue
 * Rebase* commits through git, not through a commit, so nothing is checked there either.
 *
 * [CommitCheck.ExecutionOrder.LATE], as the `api.lock` check: what is checked is the text the earlier
 * checks left. The fix is offered, not made: a choice between two imports is the user's, and the edits
 * are to files that have been reviewed. When `by` cannot answer the commit goes ahead.
 */
internal class ByMergeImportsCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler(), CommitCheck {

  private val project: Project get() = panel.project
  private val settings get() = BasedPythonSettings.getInstance(project)

  override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent = Options()

  override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

  override fun isEnabled(): Boolean = settings.reconcileMergeImportsOnCommit

  override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
    val files = commitInfo.committedChanges.mapNotNull { it.afterRevision?.file }.filter { it.name.substringAfterLast('.', "") in RECONCILED }
    if (files.isEmpty()) return null
    val pending = withContext(Dispatchers.Default) {
      withProgressText(BasedPythonBundle.message("commit.mergeImports.progress")) {
        val found = mutableListOf<Pending>()
        val byRepository = files.groupBy { path ->
          try {
            GitUtil.getRepositoryForFile(project, path)
          } catch (e: VcsException) {
            null // in no git repository
          }
        }
        for ((repository, inRepository) in byRepository) {
          repository ?: continue
          val revs = ByGitMerges.revsFor(repository.state) ?: continue
          val commits = withContext(Dispatchers.IO) { ByGitMerges.resolve(project, repository, revs) } ?: continue
          for (path in inRepository) {
            val versions = withContext(Dispatchers.IO) { ByGitMerges.versions(project, repository, commits, path) } ?: continue
            if (!versions.bothChanged) continue
            val file = path.virtualFile ?: continue
            when (ask(project, file, versions)) {
              Asked.Unsupported -> return@withProgressText emptyList()
              Asked.Nothing -> Unit
              is Asked.Reconciled -> found += Pending(file, versions)
            }
          }
        }
        found
      }
    }
    return if (pending.isEmpty()) null else Unreconciled(pending)
  }

  /** A file whose imports `by` would change, and its versions. */
  private class Pending(val file: VirtualFile, val versions: ByMergeVersions)

  private class Unreconciled(private val pending: List<Pending>) : CommitProblemWithDetails {
    override val text: String = BasedPythonBundle.message("commit.mergeImports.problem", pending.joinToString(", ") { it.file.name })
    override val showDetailsLink: String = BasedPythonBundle.message("commit.mergeImports.link")
    override val showDetailsAction: String = BasedPythonBundle.message("commit.mergeImports.action")

    override fun showDetails(project: Project) {
      project.service<ByApiLockScope>().scope.launch {
        val changed = withBackgroundProgress(project, BasedPythonBundle.message("commit.mergeImports.progress")) {
          pending.count { reconcile(project, it) }
        }
        ByCli.notifyInfo(project, BasedPythonBundle.message("notification.basedPython.title"), BasedPythonBundle.message("commit.mergeImports.done", changed))
      }
    }
  }

  /** The checkbox under *Before Commit*. */
  private inner class Options : RefreshableOnComponent {
    private val check = JBCheckBox(BasedPythonBundle.message("commit.mergeImportsName"))

    override fun getComponent(): JComponent = panel {
      row { cell(check) }
    }

    override fun saveState() {
      settings.reconcileMergeImportsOnCommit = check.isSelected
    }

    override fun restoreState() {
      check.isSelected = settings.reconcileMergeImportsOnCommit
    }
  }

  private sealed interface Asked {
    data object Unsupported : Asked
    data object Nothing : Asked
    data class Reconciled(val answer: ByReconciledImports, val merged: String) : Asked
  }

  companion object {
    /** The files whose imports are reconciled: those `by` reads as python. */
    val RECONCILED = setOf("by", "byi", "py", "pyi")

    /** How many times a file is asked again after a fix, which a choice leaves unsorted. */
    private const val ROUNDS = 4

    /** What `by` says [file]'s imports should be, from [versions] and the file as the editor holds it now. */
    private suspend fun ask(project: Project, file: VirtualFile, versions: ByMergeVersions): Asked {
      val client: LspClient = when (val start = awaitByServer(project)) {
        is ByServerStart.Running -> start.client
        is ByServerStart.Unavailable -> return Asked.Nothing.also { LOG.info("merge imports not checked: ${start.why}") }
      }
      val merged = readAction { FileDocumentManager.getInstance().getDocument(file)?.text } ?: return Asked.Nothing
      val params = ByReconcileMergeImportsParams(versions.base, versions.ours, versions.theirs, merged, file.url)
      var refusal: Throwable? = null
      val answer = awaitingAgain<ByReconciledImports>("by/reconcileMergeImports", Long.MAX_VALUE) { sent ->
        try {
          client.sendRequest { (it as ByServerExtensions).reconcileMergeImports(params).also(sent) }
        } catch (e: Exception) {
          if (e !is CancellationException) refusal = e
          throw e
        }
      }
      return when (answer) {
        is ByAnswer.Answer ->
          if (answer.value.edits.isEmpty() && answer.value.choice == null) Asked.Nothing else Asked.Reconciled(answer.value, merged)
        ByAnswer.None -> Asked.Nothing
        ByAnswer.Failed -> if (refusal?.let(::isMethodNotFound) == true) Asked.Unsupported else Asked.Nothing
      }
    }

    /**
     * Asks again for [pending]'s file as it is now and applies the answer, asking the user where `by`
     * leaves a choice, until `by` has nothing more to change. Whether the file changed.
     */
    private suspend fun reconcile(project: Project, pending: Pending): Boolean {
      var changed = false
      repeat(ROUNDS) {
        val asked = ask(project, pending.file, pending.versions) as? Asked.Reconciled ?: return changed
        val answer = asked.answer
        val edits = answer.choice?.let { choice ->
          val offered = choice.imports.filter { it.statements.isNotEmpty() }
          val picked = withContext(Dispatchers.EDT) {
            Messages.showDialog(
              project,
              BasedPythonBundle.message("commit.mergeImports.choice.message", choice.name, pending.file.name),
              BasedPythonBundle.message("commit.mergeImports.choice.title"),
              (offered.map { BasedPythonBundle.message("commit.mergeImports.choice.option", it.version, it.statements.joinToString("; ")) } +
                BasedPythonBundle.message("commit.mergeImports.choice.skip")).toTypedArray(),
              0,
              Messages.getQuestionIcon(),
            )
          }
          val version = offered.getOrNull(picked) ?: return changed
          ByMergeImports.choiceEdits(choice, version.statements)
        } ?: answer.edits
        if (!apply(project, pending.file, asked.merged, edits)) return changed
        changed = true
      }
      return changed
    }

    /**
     * [edits], made for [merged], made in [file]'s document and the document saved, so the commit,
     * which reads the disk, has them. Nothing is made where the document is no longer [merged].
     */
    private suspend fun apply(project: Project, file: VirtualFile, merged: String, edits: List<org.eclipse.lsp4j.TextEdit>): Boolean =
      withContext(Dispatchers.EDT) {
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file) ?: return@withContext false
        if (document.immutableCharSequence.toString() != merged) return@withContext false
        val replacements = ByMergeImports.replacements(document.immutableCharSequence, edits) ?: return@withContext false
        WriteCommandAction.writeCommandAction(project).withName(BasedPythonBundle.message("commit.mergeImports.command")).run<Exception> {
          for (r in replacements) document.replaceString(r.range.startOffset, r.range.endOffset, r.text)
          documents.saveDocument(document)
        }
        true
      }
  }
}
