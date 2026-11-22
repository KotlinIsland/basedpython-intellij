package dev.basedpython.pycharm.run.django

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** The command lines run from the *manage.py* window and Run Anything, and the commands starred there. */
@Service(Service.Level.PROJECT)
@State(name = "BasedPythonManage", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ByManageHistory : SimplePersistentStateComponent<ByManageHistory.Data>(Data()) {

    class Data : BaseState() {
        var lines by list<String>()
        var starred by list<String>()
    }

    /** The last lines run, the latest first. */
    val lines: List<String> get() = state.lines.toList()

    val starred: Set<String> get() = state.starred.toSet()

    fun ran(line: String) {
        if (line.isBlank()) return
        state.lines = (listOf(line) + state.lines.filter { it != line }).take(KEPT).toMutableList()
    }

    fun star(command: String, on: Boolean) {
        state.starred = (state.starred.filter { it != command } + if (on) listOf(command) else emptyList()).toMutableList()
    }

    companion object {
        private const val KEPT = 20

        fun getInstance(project: Project): ByManageHistory = project.service()
    }
}
