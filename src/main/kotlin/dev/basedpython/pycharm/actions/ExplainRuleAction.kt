package dev.basedpython.pycharm.actions

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.inspections.explain.ByRuleExplainer
import dev.basedpython.pycharm.inspections.explain.ByRuleExplanation
import dev.basedpython.pycharm.markup.ByCodeSpans
import dev.basedpython.pycharm.util.BasedPythonBundle

/** Show a balloon explaining the rule code under the caret (or prompt for one). */
class ExplainRuleAction : AnAction() {

    private val ruleRegex = Regex("""\b([A-Z]{1,4}\d{2,4})\b""")

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val code = detectRuleAtCaret(project, editor) ?: promptForCode(project) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("progress.explainingRule", code), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                when (val explanation = ByRuleExplainer.explain(project, code)) {
                    is ByRuleExplanation.Found -> showBalloon(project, editor, code, explanation.body)
                    is ByRuleExplanation.NotFound -> ByCli.notifyError(
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
            // `by explain rule` answers in markdown, so its `code` spans are marked up as code.
            append(ByCodeSpans.toHtml(body))
            append("</body></html>")
        }
        ApplicationManager.getApplication().invokeLater {
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(html, null, JBColor.background(), null)
                .setHideOnAction(true)
                .setHideOnClickOutside(true)
                .setHideOnKeyOutside(true)
                .setFadeoutTime(0)
                .createBalloon()
            if (editor != null) {
                val point = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
                balloon.show(point, Balloon.Position.above)
            } else {
                val frame = WindowManager.getInstance().getFrame(project)
                if (frame != null) {
                    balloon.show(RelativePoint.getCenterOf(frame.rootPane), Balloon.Position.above)
                }
            }
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun promptForCode(project: Project): String? {
        var result: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            result = Messages.showInputDialog(
                project,
                BasedPythonBundle.message("explainRule.prompt.message"),
                BasedPythonBundle.message("explainRule.prompt.title"),
                Messages.getQuestionIcon(),
            )
        }
        return result?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun detectRuleAtCaret(project: Project, editor: Editor?): String? {
        if (editor == null) return null
        return try {
            val offset = editor.caretModel.offset
            val markup = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
            markup.allHighlighters.asSequence()
                .filter { offset in it.startOffset..it.endOffset }
                .mapNotNull { h ->
                    val info = h.errorStripeTooltip as? HighlightInfo
                    val text = info?.description ?: info?.toolTip ?: (h.errorStripeTooltip as? String)
                    text?.let { ruleRegex.find(it)?.value }
                }
                .firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }
}
