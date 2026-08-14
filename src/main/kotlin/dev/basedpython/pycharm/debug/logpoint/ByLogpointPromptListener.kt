package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener

/**
 * Opens the inline editor the moment a log point is created from the gutter gap.
 *
 * A click in the gap goes through the platform's ordinary toggle action, which creates the
 * breakpoint and knows nothing about prompting for its expression — so an unfilled log point *is*
 * the signal that someone just made one and has not said what to log yet. Nothing else produces one:
 * the `print` quick fix supplies an expression, and one restored from the workspace kept whichever
 * it was saved with (an empty one is removed rather than saved, see [ByLogpointInlineEditor]).
 *
 * Deferred to the next event: the breakpoint's own gutter highlighter is installed as part of adding
 * it, and placing an inlay from inside that notification would be reentrant.
 */
class ByLogpointPromptListener(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

    override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
        if (!ByLogpoints.pluginOwnsLogpointPrompt()) return
        val logpoint = ByLogpoints.asLogpoint(breakpoint) ?: return
        if (!ByLogpoints.isUnfilled(logpoint)) return

        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            // Still unfilled: between the click and here the user may have done anything at all,
            // including removing it again.
            if (!ByLogpoints.isUnfilled(logpoint)) return@invokeLater
            val editor = editorShowing(logpoint) ?: return@invokeLater
            ByLogpointInlineEditor.show(project, editor, logpoint)
        }, project.disposed)
    }

    /**
     * The open editor this log point was placed in, if it is the one in front. Only the selected
     * editor: a click in a gutter happens in exactly one of them, and prompting in a split view the
     * user is not looking at would put a focused field somewhere they cannot see.
     */
    private fun editorShowing(logpoint: com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>): EditorEx? {
        val file = logpoint.sourcePosition?.file ?: return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor as? EditorEx ?: return null
        return editor.takeIf { FileDocumentManager.getInstance().getFile(it.document) == file }
    }
}
