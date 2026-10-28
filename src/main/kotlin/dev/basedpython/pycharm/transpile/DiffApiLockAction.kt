package dev.basedpython.pycharm.transpile

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.apilock.ByApiLockAnswer
import dev.basedpython.pycharm.apilock.ByApiLockScope
import dev.basedpython.pycharm.apilock.ByApiLocks
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path

/**
 * *Diff api.lock*: the project's `api.lock`, as the IDE holds it, beside the one `by/apiLock`
 * generates now, so the user can see what public-API surface has changed. Nothing is written.
 */
class DiffApiLockAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ByApiLockScope>().scope.launch {
            val answer = withBackgroundProgress(project, BasedPythonBundle.message("progress.diffingApiLock")) { ByApiLocks.ask(project) }
            val lock = when (answer) {
                is ByApiLockAnswer.Not -> return@launch ByCli.notifyError(project, BasedPythonBundle.message("notification.diffApiLockFailed.title"), answer.why)
                is ByApiLockAnswer.Lock -> answer.lock
            }
            val current = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(URI(lock.uri))) }
            withContext(Dispatchers.EDT) {
                val factory = DiffContentFactory.getInstance()
                // the lockfile as the IDE holds it, unsaved edits included; empty when there is none yet
                val left = current?.let { factory.create(project, it) } ?: factory.createEmpty()
                DiffManager.getInstance().showDiff(
                    project,
                    SimpleDiffRequest(
                        BasedPythonBundle.message("apiLock.diff.title"),
                        left,
                        factory.create(project, lock.text, PlainTextFileType.INSTANCE),
                        BasedPythonBundle.message("apiLock.diff.current"),
                        BasedPythonBundle.message("apiLock.diff.generated"),
                    ),
                )
            }
        }
    }
}
