package dev.basedpython.pycharm.inspections.explain

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Alt+Enter intention that explains the lint rule code under the caret in a `.by` file.
 *
 * Mirrors the CLI invocation and display approach of
 * [dev.basedpython.pycharm.actions.ExplainRuleAction], but surfaces it as an editor intention.
 */
class ExplainRuleIntention : IntentionAction {

    private val ruleRegex = Regex("""\b([A-Z]{1,4}\d{2,4})\b""")

    override fun getFamilyName(): String = BasedPythonBundle.message("intention.explainRule.familyName")

    override fun getText(): String {
        val code = lastDetectedCode
        return if (code != null) BasedPythonBundle.message("intention.explainRule.textWithCode", code) else BasedPythonBundle.message("intention.explainRule.familyName")
    }

    // Cached so getText() can show the concrete code resolved during isAvailable().
    @Volatile
    private var lastDetectedCode: String? = null

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        if (file.fileType != BasedPythonFileType.INSTANCE) return false
        val code = detectRuleAtCaret(project, editor)
        lastDetectedCode = code
        return code != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val code = detectRuleAtCaret(project, editor) ?: lastDetectedCode ?: return

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
            append(escape(body).replace("\n", "<br/>"))
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

    /**
     * Detect a rule code under the caret. First inspects diagnostic highlighters at the offset
     * (matching the existing action), then falls back to the word the caret sits on.
     */
    private fun detectRuleAtCaret(project: Project, editor: Editor?): String? {
        if (editor == null) return null
        return try {
            val offset = editor.caretModel.offset
            fromHighlighters(project, editor, offset) ?: fromCaretWord(editor, offset)
        } catch (_: Throwable) {
            null
        }
    }

    private fun fromHighlighters(project: Project, editor: Editor, offset: Int): String? {
        val markup = DocumentMarkupModel.forDocument(editor.document, project, false) ?: return null
        return markup.allHighlighters.asSequence()
            .filter { offset in it.startOffset..it.endOffset }
            .mapNotNull { h ->
                val info = h.errorStripeTooltip as? HighlightInfo
                val text = info?.description ?: info?.toolTip ?: (h.errorStripeTooltip as? String)
                text?.let { ruleRegex.find(it)?.value }
            }
            .firstOrNull()
    }

    private fun fromCaretWord(editor: Editor, offset: Int): String? {
        val text = editor.document.charsSequence
        if (text.isEmpty()) return null
        fun isWordChar(c: Char) = c.isLetterOrDigit()
        var start = offset.coerceIn(0, text.length)
        var end = start
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.length && isWordChar(text[end])) end++
        if (start >= end) return null
        val word = text.subSequence(start, end).toString()
        val match = ruleRegex.find(word) ?: return null
        return if (match.value == word) match.value else null
    }
}
