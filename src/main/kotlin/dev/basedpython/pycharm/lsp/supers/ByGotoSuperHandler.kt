package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.navigateToLocation
import com.intellij.psi.PsiFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JList

/**
 * Go to Super (Ctrl+U) for basedpython: from a class to its bases.
 *
 * Without this the action is dead in a `.by` file — the platform finds no handler registered for
 * the language and does nothing at all. Where to go is [BySupers]'s, which asks `by`; this runs the
 * asking and shows the answer.
 *
 * The asking is off the EDT under a modal progress, which the user can cancel and which cancels the
 * requests with it. One target is gone to straight away; several are offered in a chooser, in the
 * order `by` gives them. Nowhere to go is said in a hint rather than left silent, because a
 * shortcut that does nothing reads as a shortcut that is broken.
 */
class ByGotoSuperHandler : CodeInsightActionHandler {

    /** Navigation writes nothing, and the platform must not take a write lock for it. */
    override fun startInWriteAction(): Boolean = false

    override fun invoke(project: Project, editor: Editor, psiFile: PsiFile) {
        val file = psiFile.originalFile.virtualFile ?: return
        val server = byServerFor(project, file) ?: return hint(editor, BasedPythonBundle.message("goto.super.noServer"))
        val offset = editor.caretModel.offset
        val answer = runWithModalProgressBlocking(project, BasedPythonBundle.message("goto.super.progress")) {
            BySupers.find(server, file, editor.document, offset)
        }
        if (editor.isDisposed) return
        when (answer) {
            is BySuperAnswer.Nowhere -> hint(editor, answer.message)
            is BySuperAnswer.Targets -> go(server, editor, answer)
        }
    }

    private fun go(server: LspClient, editor: Editor, answer: BySuperAnswer.Targets) {
        val single = answer.targets.singleOrNull()
        if (single != null) return navigateToLocation(server, single.location)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(answer.targets)
            .setTitle(answer.chooserTitle)
            .setRenderer(TargetRenderer())
            .setItemChosenCallback { navigateToLocation(server, it.location) }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun hint(editor: Editor, text: String) {
        LOG.debug("Go to Super goes nowhere: $text")
        HintManager.getInstance().showInformationHint(editor, text)
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

    private companion object {
        val LOG = logger<ByGotoSuperHandler>()
    }
}
