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

/**
 * Action: "Explain Transpilation" (FEATURES.md §185).
 *
 * Runs `by transpile` on the current .by file, feeds the basedpython source plus the generated
 * Python to the pure [TranspilationExplainer], and shows a human-readable, structured report of the
 * basedpython-specific constructs that were transformed (null-safe access, elvis, data classes,
 * pattern matching, etc.).  This is a deterministic, NON-AI "AI-assist hook": it exposes the
 * basedpython -> python mapping in a form a human (or AI) can consume.
 *
 * The action is intentionally thin — all recognition lives in [TranspilationExplainer].  Invocation
 * and display follow the existing transpile-action conventions
 * (see [dev.basedpython.pycharm.transpile.ShowTranspiledDiffAction] and
 * [dev.basedpython.pycharm.transpile.selection.TranspileSelectionAction]).
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

        // Prefer the live (possibly unsaved) document text so the explanation matches what the
        // user sees; fall back to disk contents.
        val bySource = FileDocumentManager.getInstance().getDocument(file)?.text
            ?: runCatching { String(file.contentsToByteArray(), file.charset) }.getOrNull()
            ?: ""

        val path = file.toNioPath()
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Explaining transpilation of ${file.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val out = ByCli.run(project, "transpile", path.toString(), cwd = path.parent) ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(
                            project,
                            "by transpile failed",
                            out.stderr.ifBlank { "exit ${out.exitCode}" },
                        )
                        return
                    }

                    val notes = TranspilationExplainer.explain(bySource, out.stdout)
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
        fun renderHtml(fileName: String, notes: List<TranspilationNote>): String = buildString {
            append("<html><body style='font-family:sans-serif'>")
            append("<b>Explain Transpilation: ").append(escape(fileName)).append("</b><br/>")
            if (notes.isEmpty()) {
                append("<i>No basedpython-specific constructs were recognized.</i>")
            } else {
                append("<ul>")
                for (note in notes) {
                    append("<li>")
                    append("<b>").append(escape(note.constructName)).append("</b>")
                    append(" (line ").append(note.lineNumber).append(")<br/>")
                    append("<code>").append(escape(note.bySnippet)).append("</code><br/>")
                    append(codeSpans(escape(note.explanation)))
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

        /**
         * Render markdown-style `code` spans in explanation text as real `<code>` elements, so the
         * backticks don't reach the user verbatim. Must run *after* [escape], otherwise the tags it
         * emits would themselves be escaped. An unpaired backtick is left alone.
         */
        private fun codeSpans(escaped: String): String =
            escaped.replace(Regex("`([^`]+)`"), "<code>$1</code>")
    }
}
