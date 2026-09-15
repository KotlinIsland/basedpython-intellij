package dev.basedpython.pycharm.inspections.explain

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.markup.ByCodeSpans
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Diagnostic

/**
 * *Explain rule `code`*, offered on the diagnostic that carries [code].
 *
 * The code is the one the server put on the diagnostic, so it is right for every rule either tool
 * has — `buff`'s `F401` and `by`'s `invalid-argument-type` alike — rather than whatever a pattern
 * over the message text could recognise, and each diagnostic carries its own: two editors, or two
 * diagnostics under one caret, cannot hand each other theirs.
 */
internal class ExplainRuleFix(val code: String) : IntentionAction, LowPriorityAction {

    override fun getText(): String = BasedPythonBundle.message("intention.explainRule.textWithCode", code)

    override fun getFamilyName(): String = BasedPythonBundle.message("intention.explainRule.familyName")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        ExplainRule.show(project, editor, code, file?.virtualFile)
    }

    companion object {
        /** The code [diagnostic] carries, as the servers spell it, or null when it has none. */
        fun codeOf(diagnostic: Diagnostic): String? {
            val code = diagnostic.code ?: return null
            return (if (code.isLeft) code.left else code.right?.toString())?.takeIf { it.isNotBlank() }
        }

        /** The codes of the diagnostics under [offset] in [editor], nearest first as the markup lists them. */
        fun codesAt(project: Project, editor: Editor, offset: Int): List<String> {
            val markup = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return emptyList()
            return markup.allHighlighters.asSequence()
                .filter { offset in it.startOffset..it.endOffset }
                .mapNotNull { HighlightInfo.fromRangeHighlighter(it) }
                .mapNotNull { info -> info.findRegisteredQuickFix { descriptor, _ -> (descriptor.action as? ExplainRuleFix)?.code } }
                .distinct()
                .toList()
        }
    }
}

/** Looks a rule up off the EDT and shows what came back beside the caret. */
internal object ExplainRule {

    fun show(project: Project, editor: Editor?, code: String, contextFile: VirtualFile?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("progress.explainingRule", code), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                when (val explanation = ByRuleExplainer.explain(project, code, contextFile)) {
                    is ByRuleExplanationResult.Found -> showBalloon(project, editor, code, explanation.body)
                    is ByRuleExplanationResult.NotFound -> ByCli.notifyError(
                        project,
                        BasedPythonBundle.message("explainRule.noExplanationFor.title", code),
                        explanation.message,
                    )
                }
            }
        })
    }

    private fun showBalloon(project: Project, editor: Editor?, code: String, body: String) {
        val html = buildString {
            append("<html><body style='font-family:sans-serif'>")
            append("<b>").append(escape(code)).append("</b><br/>")
            // Both servers answer in markdown, so its `code` spans are marked up as code.
            append(ByCodeSpans.toHtml(body))
            append("</body></html>")
        }
        ApplicationManager.getApplication().invokeLater({
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(html, null, JBColor.background(), null)
                .setHideOnAction(true)
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .setFadeoutTime(0)
                .createBalloon()
            if (editor != null && !editor.isDisposed) {
                val point = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
                balloon.show(point, Balloon.Position.above)
            } else {
                val frame = WindowManager.getInstance().getFrame(project)
                if (frame != null) {
                    balloon.show(RelativePoint.getCenterOf(frame.rootPane), Balloon.Position.above)
                }
            }
        }, project.disposed)
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
