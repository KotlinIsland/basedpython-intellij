package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.ui.ByInlayHintsConfigurable
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import org.eclipse.lsp4j.Location

/**
 * What a right click on one of basedpython's hints offers: everything a hint can do, findable
 * rather than hidden behind gestures, and the hint's kind's settings where the hint is.
 *
 * - **Insert** — the double click, when `by` sent the edits that write the hint in.
 * - **Go to `name`** for each run of the label that names something — the Ctrl+click.
 * - **Copy** — the whole label, as it would be written, untruncated.
 * - **The kind's mode** — always, while the push key is held, or never — changed here as on the
 *   settings page, through [ByHintModeChange].
 * - **Inlay Hints Settings…** — the settings page, with this kind selected.
 *
 * The platform's declarative hints answer a right click with a menu of their own (the `InlayMenu`
 * group); the provider API these hints are drawn through has none, so each presentation opens this
 * one from its popup trigger and keeps that event from the editor, whose own context menu would
 * otherwise open on top of it.
 */
class ByHintMenu(
    private val project: Project,
    val kind: ByHintKind,
    /** The whole label, as `by` sent it. */
    private val label: String,
    /** The runs of [label] that name something, against [label] rather than the drawn text. */
    private val links: List<ByHintLink>,
    private val navigate: ((Location) -> Unit)?,
    private val accept: (() -> Unit)?,
    /** Puts the menu on screen; a test replaces it, there being no screen. */
    private val display: (DefaultActionGroup, MouseEvent) -> Unit = ::popUp,
) {

    /** Opens the menu where [event] happened. EDT. */
    fun show(event: MouseEvent) = display(group(), event)

    /** The menu's actions, in order. Built afresh for each right click, from what the hint has now. */
    internal fun group(): DefaultActionGroup {
        val group = DefaultActionGroup()
        accept?.let { accept ->
            group += action("Insert into Code") { accept() }
        }
        val navigate = navigate
        if (navigate != null) {
            // One entry per place, in label order: `dict[str, dict[str, int]]` names `dict` twice and
            // both go to the same class.
            for (link in links.distinctBy { it.location.uri to it.location.range }) {
                group += action("Go to ${label.substring(link.start, link.end)}") { navigate(link.location) }
            }
        }
        group += action("Copy Hint Text") { CopyPasteManager.getInstance().setContents(StringSelection(label)) }
        group += Separator.create(title())
        val pushKey = BasedPythonSettings.getInstance(project).inlayPushKey
        for (mode in MODES) {
            val text = when (mode) {
                ByHintMode.ALWAYS -> "Always Show"
                ByHintMode.ON_PUSH -> "Show While ${pushKey.display} Is Held"
                ByHintMode.NEVER -> "Never Show"
            }
            group += object : DumbAwareToggleAction(text) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent): Boolean = BasedPythonSettings.getInstance(project).inlayMode(kind) == mode
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) ByHintModeChange.apply(project, mapOf(kind to mode))
                }
            }
        }
        group += Separator.create()
        group += action("Inlay Hints Settings…") { ByInlayHintsConfigurable.show(project, kind) }
        return group
    }

    /** The kind's name as `by`'s catalog gives it, or its option name before a catalog has been seen. */
    private fun title(): String = ByInlayCatalog.getInstance().catalog?.kind(kind)?.title?.takeIf { it.isNotBlank() }
        ?: kind.setting.ifEmpty { "Other" }

    private fun action(text: String, run: () -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private operator fun DefaultActionGroup.plusAssign(action: AnAction) = add(action)

    internal companion object {
        private fun popUp(group: DefaultActionGroup, event: MouseEvent) {
            val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.EDITOR_INLAY, group)
            popup.component.show(event.component, event.x, event.y)
        }

        val MODES = listOf(ByHintMode.ALWAYS, ByHintMode.ON_PUSH, ByHintMode.NEVER)

        /**
         * Whether [event] is the one that opens a context menu: the press on macOS and Linux, the
         * release on Windows — Swing's `isPopupTrigger` says which for the platform it runs on.
         */
        fun isTrigger(event: MouseEvent): Boolean = event.isPopupTrigger
    }
}
