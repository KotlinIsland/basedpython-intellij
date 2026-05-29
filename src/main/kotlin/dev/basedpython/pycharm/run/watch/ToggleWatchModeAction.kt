package dev.basedpython.pycharm.run.watch

import dev.basedpython.pycharm.actions.ByCli
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Toggles the per-project basedpython watch-mode flag and posts a notification
 * with the new state.
 */
internal class ToggleWatchModeAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val enabled = WatchModeState.toggle(project)
        ByCli.notifyInfo(
            project,
            "BasedPython Watch Mode",
            "Watch mode: " + if (enabled) "ON" else "OFF",
        )
    }
}
