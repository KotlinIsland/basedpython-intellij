package dev.basedpython.pycharm.run.watch

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

/**
 * Per-project on/off flag for basedpython watch mode (auto `by build` on save).
 *
 * Stored in [PropertiesComponent.getInstance] for the project under
 * [KEY] so it survives restarts without touching the settings data class.
 */
internal object WatchModeState {

    const val KEY: String = "dev.basedpython.pycharm.watchMode"

    fun isEnabled(project: Project): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(KEY, false)

    /** Flip the flag and return the new value. */
    fun toggle(project: Project): Boolean {
        val next = !isEnabled(project)
        PropertiesComponent.getInstance(project).setValue(KEY, next)
        return next
    }
}
