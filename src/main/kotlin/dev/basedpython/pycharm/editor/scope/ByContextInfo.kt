package dev.basedpython.pycharm.editor.scope

import com.intellij.codeInsight.hint.EditorFragmentComponent
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.LightweightHint
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * *View | Context Info* (Ctrl+Shift+Q on macOS, Alt+Q elsewhere) in a `.by` editor: the header of the
 * class or function the caret is in, shown over the top of the editor when it has scrolled out of
 * sight, as PyCharm shows a `.py`'s.
 *
 * ## Why an action of its own
 *
 * The platform's action (`EditorContextInfo`, internal) finds the container as the structure view
 * model's current element, and goes on only when that is a `PsiElement` whose parents a
 * `declarationRangeHandler` gives a header. The structure of a `.by` file is the platform's LSP
 * model of `by`'s document symbols, whose current element is a `DocumentSymbol`, so for `.by` it
 * did nothing; and handing it a `PsiElement` instead would leave the Structure view's selection,
 * which matches the current element against the symbols, selecting nothing. The action takes no
 * per-language handler. So this is a second action on the same shortcut ([ByContextInfoPromoter]),
 * enabled only in a `.by` editor; elsewhere the keystroke stays the platform's. The menu item is
 * still the platform's, and in `.by` still does nothing.
 *
 * ## What is shown
 *
 * The platform's rule, as its `ShowContainerInfoHandler` has it: of the definitions around the
 * caret, the innermost whose header begins above the top of the editor; none when every one is in
 * sight. Invoked again while that is shown, the next one out. The definitions are a `.py`'s, classes
 * and functions and not an `if` or a `for` (measured in PyCharm 263.7649) — here the statements
 * `by`'s outline names ([ByEnclosingScopes]). The header is from its first decorator to its `:`,
 * where `.py`'s ends at the parameter list's `)` or the base list's: the same lines but for a return
 * annotation wrapped onto a line of its own, which this shows as well.
 *
 * The outline is the one [ByOutlines] holds for the text on screen; without one, nothing is shown,
 * and one is asked for.
 */
class ByContextInfoAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = editor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = editor(e) ?: return
        ByContextInfo.getInstance().show(editor)
    }

    /** The editor of a `.by` file this acts on, as the platform's action finds its editor. */
    private fun editor(e: AnActionEvent): Editor? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR_EVEN_IF_INACTIVE) ?: return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document)
        return editor.takeIf { file is BasedPythonFile }
    }
}

/**
 * Puts [ByContextInfoAction] first among the actions of a keystroke. It is disabled outside a `.by`
 * editor, so there the keystroke goes on to the platform's Context Info.
 */
class ByContextInfoPromoter : ActionPromoter {
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> =
        actions.filterIsInstance<ByContextInfoAction>()
}

/**
 * The hint each editor last showed, so that invoking the action again while it is up goes one
 * definition further out. Kept here rather than on the editor, and weakly: an editor closed takes
 * its entry with it, and nothing of the plugin's is left on one when it unloads.
 *
 * EDT only.
 */
@Service(Service.Level.APP)
internal class ByContextInfo {

    private class Shown(val hint: WeakReference<LightweightHint>, val header: Int, val stamp: Long)

    private val shown = WeakHashMap<Editor, Shown>()

    fun show(editor: Editor) {
        val document = editor.document
        val previous = shown.remove(editor)
        val previousHint = previous?.hint?.get()
        val showing = previousHint != null && previousHint.isVisible && previous.stamp == document.modificationStamp
        previousHint?.hide()
        val header = headerToShow(editor, previous?.header?.takeIf { showing }) ?: return
        val stamp = document.modificationStamp
        // later, as the platform's does: the keystroke that asked would otherwise be the key that hides it
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || document.modificationStamp != stamp) return@invokeLater
            val hint = EditorFragmentComponent.showEditorFragmentHint(editor, header, true, true) ?: return@invokeLater
            shown[editor] = Shown(WeakReference(hint), header.startOffset, stamp)
        }
    }

    /**
     * The header to show for [editor]'s caret, after the one starting at [after] when that one is
     * showing already; null when there is none, or no outline of the text on screen.
     */
    internal fun headerToShow(editor: Editor, after: Int? = null): TextRange? {
        val project = editor.project ?: return null
        val outline = ByOutlines.getInstance(project).current(editor.document) ?: return null
        val headers = ByEnclosingScopes.at(outline, editor.caretModel.offset)
            .filter { it.isDefinition }
            .map { it.header }
        return choose(headers, after) { headerInSight(editor, it) }
    }

    /** Whether the line [header] starts on is at or below the top of what [editor] shows, as the platform asks. */
    private fun headerInSight(editor: Editor, header: TextRange): Boolean =
        editor.logicalPositionToXY(editor.offsetToLogicalPosition(header.startOffset)).y >= editor.scrollingModel.visibleArea.y

    companion object {
        fun getInstance(): ByContextInfo = service()

        /**
         * Which of [headers], the definitions around the caret innermost first, to show: the first
         * not [inSight] — after the one starting at [after], when that one is showing already.
         */
        fun choose(headers: List<TextRange>, after: Int?, inSight: (TextRange) -> Boolean): TextRange? {
            val from = after?.let { start -> headers.indexOfFirst { it.startOffset == start } + 1 } ?: 0
            return headers.drop(from).firstOrNull { !inSight(it) }
        }
    }
}
