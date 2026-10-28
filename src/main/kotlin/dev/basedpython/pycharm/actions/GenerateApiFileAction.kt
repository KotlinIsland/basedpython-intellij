package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.basedpython.pycharm.apilock.ByApiLockAnswer
import dev.basedpython.pycharm.apilock.ByApiLockScope
import dev.basedpython.pycharm.apilock.ByApiLocks
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Generate api.lock*: writes the project's lockfile as `by/apiLock` generates it now, unsaved edits
 * included, where `by` says the project keeps it, and opens it. Undoable, like any other edit.
 */
class GenerateApiFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ByApiLockScope>().scope.launch {
            val answer = withBackgroundProgress(project, BasedPythonBundle.message("progress.generatingApiLock")) { ByApiLocks.ask(project) }
            when (answer) {
                is ByApiLockAnswer.Not ->
                    ByCli.notifyError(project, BasedPythonBundle.message("notification.generateApiFailed.title"), answer.why)
                is ByApiLockAnswer.Lock -> {
                    val file = ByApiLocks.write(project, answer.lock)
                    ByCli.notifyInfo(
                        project,
                        BasedPythonBundle.message("notification.basedPython.title"),
                        BasedPythonBundle.message("notification.apiLockGenerated.records", ByApiLocks.records(answer.lock.text)),
                    )
                    if (file != null) withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file).navigate(true) }
                }
            }
        }
    }
}
