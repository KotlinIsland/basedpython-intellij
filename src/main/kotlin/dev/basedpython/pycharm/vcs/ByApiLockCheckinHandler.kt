package dev.basedpython.pycharm.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.withProgressText
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.apilock.ByApiLockAnswer
import dev.basedpython.pycharm.apilock.ByApiLockScope
import dev.basedpython.pycharm.apilock.ByApiLocks
import dev.basedpython.pycharm.lsp.API_LOCK
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

private val LOG = Logger.getInstance(ByApiLockCheckinHandler::class.java)

internal class ByApiLockCheckinHandlerFactory : CheckinHandlerFactory() {
  override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler =
    ByApiLockCheckinHandler(panel)
}

/**
 * Stops a commit whose `api.lock` is stale — a merge commit too — and offers to regenerate it.
 *
 * Asked of `by/apiLock`, which compares the lockfile with what the project's sources say now, as the
 * editor holds them; the check is about the working copy, so a commit of part of the changes is
 * checked against all of them. Only a commit that touches a source `by` reads or the lockfile itself
 * is checked, and only a project that has a lockfile. When `by` cannot answer — not running, or a
 * `by` from before the request — the commit goes ahead: this check exists to catch a stale lockfile,
 * and a commit blocked on a missing server would be a different failure.
 *
 * [CommitCheck.ExecutionOrder.LATE], after the checks that rewrite files, so the sources it compares
 * with are the ones committed. On by default: a stale lockfile in history is what `api.lock` exists to
 * prevent.
 */
internal class ByApiLockCheckinHandler(private val panel: CheckinProjectPanel) : CheckinHandler(), CommitCheck {

  private val project: Project get() = panel.project
  private val settings get() = BasedPythonSettings.getInstance(project)

  override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent = Options()

  override fun getExecutionOrder(): CommitCheck.ExecutionOrder = CommitCheck.ExecutionOrder.LATE

  override fun isEnabled(): Boolean = settings.checkApiLockOnCommit

  override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
    val touched = commitInfo.committedChanges.mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.name }
    if (touched.none(::checks)) return null
    val answer = withContext(Dispatchers.Default) {
      withProgressText(BasedPythonBundle.message("commit.apiLock.progress")) { ByApiLocks.ask(project) }
    }
    val lock = when (answer) {
      is ByApiLockAnswer.Lock -> answer.lock
      is ByApiLockAnswer.Not -> return null.also { LOG.info("api.lock not checked: ${answer.why}") }
    }
    val line = lock.staleLine?.takeIf { lock.exists } ?: return null
    return Stale(line, committed = API_LOCK in touched)
  }

  /**
   * The lockfile is stale; the link writes it as `by` generates it now. A commit check cannot add a
   * file to the commit: what is committed is the commit's own inclusion, which no public API reaches,
   * and git4idea resets anything else staged around the commit. So where the lockfile is not
   * [committed], the text says to include it.
   */
  private class Stale(line: Int, committed: Boolean) : CommitProblemWithDetails {
    override val text: String =
      BasedPythonBundle.message(if (committed) "commit.apiLock.stale" else "commit.apiLock.stale.include", line + 1)
    override val showDetailsLink: String = BasedPythonBundle.message("commit.apiLock.stale.link")
    /** A button in title case where the commit asks in a dialog, which would make `api.lock` read "Api.Lock". */
    override val showDetailsAction: String = BasedPythonBundle.message("commit.apiLock.stale.action")

    /** Asked again rather than writing what the check saw: the sources may have changed since. */
    override fun showDetails(project: Project) {
      project.service<ByApiLockScope>().scope.launch {
        when (val answer = withBackgroundProgress(project, BasedPythonBundle.message("progress.generatingApiLock")) { ByApiLocks.ask(project) }) {
          is ByApiLockAnswer.Lock -> ByApiLocks.write(project, answer.lock)
          is ByApiLockAnswer.Not -> ByCli.notifyError(project, BasedPythonBundle.message("notification.generateApiFailed.title"), answer.why)
        }
      }
    }
  }

  /** The checkbox under *Before Commit*. */
  private inner class Options : RefreshableOnComponent {
    private val check = JBCheckBox(BasedPythonBundle.message("commit.apiLockName"))

    override fun getComponent(): JComponent = panel {
      row { cell(check) }
    }

    override fun saveState() {
      settings.checkApiLockOnCommit = check.isSelected
    }

    override fun restoreState() {
      check.isSelected = settings.checkApiLockOnCommit
    }
  }

  companion object {
    /** Whether a commit that touches a file named [name] is checked: a source `by` reads, or the lockfile. */
    fun checks(name: String): Boolean = name == API_LOCK || name.substringAfterLast('.', "") in setOf("by", "byi", "py", "pyi")
  }
}
