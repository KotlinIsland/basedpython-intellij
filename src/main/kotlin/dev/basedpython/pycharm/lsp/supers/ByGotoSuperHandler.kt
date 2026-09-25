package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.navigateToLocation
import com.intellij.psi.PsiFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import javax.swing.JList

/**
 * Go to Super (Ctrl+U) for basedpython: from a class to its bases, and from a class member to the
 * members it overrides.
 *
 * Without this the action is dead in a `.by` file — the platform finds no handler registered for
 * the language and does nothing at all. Where to go is [BySupers]'s, which asks `by`; this runs the
 * asking and shows the answer.
 *
 * One target is gone to straight away; several are offered in a chooser, in the order `by` gives
 * them. Nowhere to go is said in a hint rather than left silent, because a shortcut that does
 * nothing reads as a shortcut that is broken.
 *
 * ## Asking in the background, not under a modal progress
 *
 * [BySupers] names the text it asks about, and `by` answers a document the platform has not sent
 * it yet once the platform's `didOpen` brings it. The platform sends that `didOpen` from a step it
 * runs on the EDT in the non-modal state (`LspOpenedFilesService`, `finishOnUiThread(nonModal)`),
 * so a modal progress held over the asking keeps it from ever going out: Ctrl+U pressed before the
 * platform had opened the file sat in the modal for the ten seconds `by` holds a request, then said
 * `by` had not answered. Measured in PyCharm 263.5153.49: 10080ms, and no navigation. So the asking
 * runs under a background progress, which the user can cancel from the status bar and which leaves
 * the EDT free; the answer is shown when it comes, unless the caret has moved or the text changed
 * meanwhile, since it answers for a place the user has left.
 */
class ByGotoSuperHandler : CodeInsightActionHandler {

    /** Navigation writes nothing, and the platform must not take a write lock for it. */
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val file = psiFile.originalFile.virtualFile ?: return
        val server = byServerFor(project, file) ?: return hint(editor, BasedPythonBundle.message("goto.super.noServer"))
        ask(project, editor, { document, offset -> BySupers.find(server, file, document, offset) }) { answer ->
            when (answer) {
                is BySuperAnswer.Nowhere -> hint(editor, answer.message)
                is BySuperAnswer.Targets -> go(server, editor, answer)
            }
        }
    }

    /**
     * Asks [find] where Go to Super goes from [editor]'s caret, off the EDT under a background
     * progress, and hands the answer to [show] on the EDT — unless by then the editor is gone, its
     * caret has moved or its text has changed.
     */
    @VisibleForTesting
    internal fun ask(
        project: Project,
        editor: Editor,
        find: suspend (Document, Int) -> BySuperAnswer,
        show: (BySuperAnswer) -> Unit,
    ): Job {
        val document = editor.document
        val offset = editor.caretModel.offset
        val stamp = document.modificationStamp
        return project.service<ByGotoSuperScope>().scope.launch {
            val answer = withBackgroundProgress(project, BasedPythonBundle.message("goto.super.progress")) {
                find(document, offset)
            }
            withContext(Dispatchers.EDT) {
                val stillThere = !editor.isDisposed &&
                    document.modificationStamp == stamp &&
                    editor.caretModel.offset == offset
                if (stillThere) show(answer)
            }
        }
    }

    private fun go(server: LspClient, editor: Editor, answer: BySuperAnswer.Targets) {
        BySuperChooser.go(server, answer) { it.showInBestPositionFor(editor) }
    }

    private fun hint(editor: Editor, text: String) {
        LOG.debug("Go to Super goes nowhere: $text")
        HintManager.getInstance().showInformationHint(editor, text)
    }

    private companion object {
        val LOG = logger<ByGotoSuperHandler>()
    }
}

/**
 * Goes where Go to Super's answer says: to its one target, or, for several, to the one chosen from a
 * list of them in the order `by` gave — the same list from Ctrl+U and from the gutter's *overrides*
 * icon.
 */
internal object BySuperChooser {

    /** Goes to [answer]'s one target, or hands [show] the chooser among several. EDT only. */
    fun go(server: LspClient, answer: BySuperAnswer.Targets, show: (JBPopup) -> Unit) {
        val single = answer.targets.singleOrNull()
        if (single != null) return navigateToLocation(server, single.location)
        show(
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(answer.targets)
                .setTitle(answer.chooserTitle)
                .setRenderer(TargetRenderer())
                .setItemChosenCallback { navigateToLocation(server, it.location) }
                .createPopup(),
        )
    }

    /** The name, and where it lives in grey after it. */
    private class TargetRenderer : ColoredListCellRenderer<BySuperTarget>() {
        override fun customizeCellRenderer(
            list: JList<out BySuperTarget>,
            value: BySuperTarget,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            value.where?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        }
    }
}

/** Where Go to Super's asking runs: cancelled with the project, and with the plugin when it unloads. */
@Service(Service.Level.PROJECT)
internal class ByGotoSuperScope(val scope: CoroutineScope)
