package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Sets the mode of some kinds of hint, and does what a changed mode needs to reach the editor —
 * the one way to change one, from *Settings | basedpython | Inlay Hints* and from a hint's own
 * right-click menu alike.
 *
 * Which kinds `by` computes at all is read when it starts, from the modes, so a kind moved into or
 * out of [ByHintMode.NEVER] restarts it; any other change is only in how the plugin draws what it
 * already has, and redraws ([ByInlayRefresh]).
 */
object ByHintModeChange {

    /** Applies [modes]; whether anything changed. */
    fun apply(project: Project, modes: Map<ByHintKind, ByHintMode>, pushKey: ByPushKey? = null): Boolean {
        val settings = BasedPythonSettings.getInstance(project)
        var neverChanged = false
        var changed = pushKey != null && pushKey != settings.inlayPushKey
        for ((kind, after) in modes) {
            val before = settings.inlayMode(kind)
            if (before == after) continue
            changed = true
            if ((before == ByHintMode.NEVER) != (after == ByHintMode.NEVER)) neverChanged = true
            settings.setInlayMode(kind, after)
        }
        if (pushKey != null) settings.inlayPushKey = pushKey
        if (neverChanged) BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        if (changed) ByInlayRefresh.getInstance(project).request()
        return changed
    }
}
