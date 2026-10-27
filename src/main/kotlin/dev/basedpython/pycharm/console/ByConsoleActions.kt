package dev.basedpython.pycharm.console

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The *basedpython Console* tool window, which starts a console when it is first opened, as PyCharm's
 * *Python Console* does. Offered on its own in a basedpython project only.
 */
internal class ByConsoleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ByConsoles.getInstance(project).start(toolWindow)
    }
}

/** *Tools | basedpython Console*: shows the console, starting one when none is running. */
internal class ByOpenConsoleAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ByConsoles.getInstance(project).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

/**
 * *Execute Selection in basedpython Console* (Alt+Shift+E in a `.by` editor, `.py`'s shortcut for its
 * own): sends the selection, or the line the caret is on, to the console as one input, as Enter would
 * run it there. A selection is sent without the indentation its lines share, so a block picked out of
 * a function body runs at the session's top level; after a line, the caret moves to the next one.
 */
internal class ByExecuteInConsoleAction : DumbAwareAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = textToRun(editor)
        if (text.isBlank()) return
        ByConsoles.getInstance(project).show { session -> session.execute(text) }
    }

    /** The selection, or the caret's line, moving the caret past that line. */
    private fun textToRun(editor: Editor): String {
        val selection = editor.selectionModel
        if (selection.hasSelection()) return ByConsoleText.dedented(selection.selectedText.orEmpty()).trimEnd('\n', '\r')
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        if (line + 1 < document.lineCount) {
            editor.caretModel.moveToOffset(document.getLineStartOffset(line + 1))
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        return text.trim()
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        // not in the console's own input, whose Enter is what runs it
        val inConsole = project != null && file?.virtualFile?.let { ByConsoles.getInstance(project).sessionFor(it) } != null
        e.presentation.isEnabledAndVisible = project != null && editor != null && file is BasedPythonFile && !inConsole
        if (e.presentation.isEnabledAndVisible) {
            e.presentation.text = BasedPythonBundle.message(
                if (editor!!.selectionModel.hasSelection()) "console.executeSelection" else "console.executeLine",
            )
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
