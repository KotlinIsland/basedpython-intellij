package dev.basedpython.pycharm.docs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Shows a bundled, self-contained syntax quick-reference for BasedPython in an
 * HTML popup. No external services are required.
 */
class BasedPythonQuickReferenceAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = BasedPythonBundle.message("action.quickReference.text")
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val popup = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                CHEAT_SHEET,
                null,
                com.intellij.ui.JBColor.background(),
                null,
            )
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(false)
            .setFadeoutTime(0)
            .createBalloon()

        if (editor != null) {
            popup.show(
                JBPopupFactory.getInstance().guessBestPopupLocation(editor),
                com.intellij.openapi.ui.popup.Balloon.Position.below,
            )
        } else {
            val frame = WindowManager.getInstance().getFrame(project) ?: return
            popup.show(
                RelativePoint.getCenterOf(frame.rootPane),
                com.intellij.openapi.ui.popup.Balloon.Position.below,
            )
        }
    }

    private companion object {
        val CHEAT_SHEET: String = buildString {
            append("<html><body style='font-family:sans-serif;'>")
            append("<h3 style='margin:0 0 4px 0;'>basedpython Syntax Quick Reference</h3>")
            append("<table cellpadding='2' style='font-size:small;'>")

            row("let x = 42", "Immutable binding")
            row("newtype UserId = int", "Distinct nominal type")
            row("protocol Drawable:", "Structural interface")
            row("data class Point:", "Auto __init__/__eq__/__repr__")
            row("frozen data class Point:", "Immutable, hashable data class")
            row("enum class Color:", "Fixed set of named members")
            row("class def Widget:", "Class with member modifiers")
            row("override def f():", "Overrides a base member")
            row("abstract def f():", "No implementation; must override")
            row("final class C:", "Cannot be subclassed/overridden")
            row("static def f():", "Belongs to the class, no self")
            row("public name: str", "Public visibility (default)")
            row("private name: str", "Private visibility")
            row("user?.name", "Null-safe access (?.)")
            row("name ?? \"x\"", "Null-coalescing (??)")

            append("</table>")
            append("<br/><a href='${BasedPythonDocEntries.DOCS_BASE}'>Full documentation</a>")
            append("</body></html>")
        }

        fun StringBuilder.row(code: String, desc: String) {
            append("<tr><td><code>")
                .append(escape(code))
                .append("</code></td><td style='padding-left:8px;color:#888;'>")
                .append(escape(desc))
                .append("</td></tr>")
        }

        fun escape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
