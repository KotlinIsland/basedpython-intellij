package dev.basedpython.pycharm.run.ergonomics

import com.intellij.ide.macro.MacroManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger

/**
 * Reusable helper for expanding IntelliJ path macros (`$FilePath$`, `$FileName$`,
 * `$ProjectFileDir$`, `$ModuleName$`, etc.) inside run-config argument and working-directory
 * strings.
 *
 * This delegates to the platform [MacroManager], which owns the full, version-correct macro
 * set, so callers get every macro the IDE supports without us re-implementing them. The macro
 * values are resolved from the supplied [DataContext] (typically built from the current editor /
 * selected file / module).
 *
 * This is a self-contained utility intended for future wiring; it does not modify any existing
 * run configuration.
 */
object ByMacros {
    private val LOG = Logger.getInstance(ByMacros::class.java)

    /**
     * Expand all macros in [raw] using [context].
     *
     * @param raw the string possibly containing `$Macro$` tokens.
     * @param context the data context used to resolve macro values (file, module, project, ...).
     * @param firstQueueExpand passed through to [MacroManager.expandSilentMacros]; `true` expands
     *   the first-level macro queue (the common case for arg/working-dir strings).
     * @return the expanded string, or [raw] unchanged if expansion is cancelled or fails.
     */
    @JvmStatic
    @JvmOverloads
    fun expand(raw: String, context: DataContext, firstQueueExpand: Boolean = true): String {
        if (raw.isEmpty() || '$' !in raw) return raw
        return try {
            MacroManager.getInstance().expandSilentMacros(raw, firstQueueExpand, context) ?: raw
        } catch (e: Exception) {
            // Macro.ExecutionCancelledException and any resolution error: fall back to the raw value.
            LOG.debug("Macro expansion failed for \"$raw\"", e)
            raw
        }
    }
}
