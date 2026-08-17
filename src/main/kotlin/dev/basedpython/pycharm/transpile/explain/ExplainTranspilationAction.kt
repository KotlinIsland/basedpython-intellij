package dev.basedpython.pycharm.transpile.explain

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.ext.ByTranspilationNote
import dev.basedpython.pycharm.markup.ByCodeSpans

/**
 * Action: "Explain Transpilation" (FEATURES.md §185).
 *
 * Shows a structured report of the basedpython-specific constructs the file uses — null-safe
 * access, coalescing, data classes, pattern matching — and what each lowers to.  A deterministic,
 * NON-AI "AI-assist hook": it exposes the basedpython → python mapping in a form a human (or an AI)
 * can consume.
 *
 * The recognition is the server's, off the parse tree the transpiler itself runs on.  It used to be
 * a regex per construct here, over the source text, which cannot tell an operator from the same
 * characters inside a string or a comment and drifts from the language the moment it grows one.
 */
class ExplainTranspilationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Explaining transpilation of ${file.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val notes = ByTranspilationNotes.of(project, file) ?: return
                    val html = renderHtml(file.name, notes)
                    ApplicationManager.getApplication().invokeLater {
                        showReport(project, editor, html)
                    }
                }
            },
        )
    }

    private fun showReport(project: Project, editor: Editor?, html: String) {
        if (project.isDisposed) return
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(html, null, JBColor.background(), null)
            .setHideOnAction(true)
            .setHideOnClickOutside(true)
            .setHideOnKeyOutside(true)
            .setFadeoutTime(0)
            .createBalloon()
        if (editor != null) {
            val point = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
            balloon.show(point, com.intellij.openapi.ui.popup.Balloon.Position.above)
        } else {
            val frame = WindowManager.getInstance().getFrame(project)
            if (frame != null) {
                balloon.show(RelativePoint.getCenterOf(frame.rootPane), com.intellij.openapi.ui.popup.Balloon.Position.above)
            }
        }
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)

    companion object {
        /** Build the HTML report for [notes]. Exposed for reuse/testing of the rendering. */
        fun renderHtml(fileName: String, notes: List<ByTranspilationNote>): String = buildString {
            append("<html><body style='font-family:sans-serif'>")
            append("<b>Explain Transpilation: ").append(escape(fileName)).append("</b><br/>")
            if (notes.isEmpty()) {
                append("<i>No basedpython-specific constructs were recognized.</i>")
            } else {
                append("<ul>")
                for (note in notes) {
                    append("<li>")
                    append("<b>").append(escape(note.construct.orEmpty())).append("</b>")
                    append(" (line ").append(note.line).append(")<br/>")
                    append("<code>").append(escape(note.snippet.orEmpty())).append("</code><br/>")
                    append(ByCodeSpans.toHtml(note.explanation.orEmpty()))
                    append("</li>")
                }
                append("</ul>")
            }
            append("</body></html>")
        }

        private fun escape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
