package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByLineBreakpointType

/**
 * Adds a log point above the caret's line, or opens the one already there — the keyboard's way in to
 * the gutter gap.
 *
 * Shares `Ctrl+Alt+F8` with IntelliJ IDEA's own *Add Logpoint*, and gives way to it: where the IDE
 * ships the logpoints feature this action reports itself disabled, and the action system passes the
 * keystroke to the enabled one bound to the same shortcut.
 *
 * "Above the caret's line" and "anchored to the caret's line" are the same statement — an inter-line
 * breakpoint belongs to the line below its gap.
 */
class ByAddLogpointAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ByLogpoints.pluginProvidesLogpointUi() && target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val (project, editor, file, line) = target(e) ?: return
        // Adding is enough on its own: ByLogpointFields puts a box on every log point, so creating
        // one here and showing it as well would race its own listener.
        val logpoint = existing(project, file, line) ?: create(project, file, line)
        ByLogpointField.show(project, editor, logpoint)?.expressionEditor
            ?.preferredFocusedComponent?.requestFocusInWindow()
    }

    private data class Target(val project: Project, val editor: EditorEx, val file: VirtualFile, val line: Int)

    /** The `.by` editor and line this would act on, or null when the caret is not in one. */
    private fun target(e: AnActionEvent): Target? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) as? EditorEx ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        if (!file.extension.equals("by", ignoreCase = true)) return null
        return Target(project, editor, file, editor.caretModel.logicalPosition.line)
    }

    private fun existing(project: Project, file: VirtualFile, line: Int): XLineBreakpoint<*>? =
        XDebuggerManager.getInstance(project).breakpointManager
            .findBreakpointsAtLine(type(), file, line, XLineBreakpointVerticalPlacement.INTER_LINE)
            .firstOrNull()

    private fun create(project: Project, file: VirtualFile, line: Int): XLineBreakpoint<*> {
        val info = XLineBreakpointAdditionalInfo.Builder()
            .setVerticalPlacement(XLineBreakpointVerticalPlacement.INTER_LINE)
            .setSuspendPolicy(SuspendPolicy.NONE)
            .build()
        return XDebuggerManager.getInstance(project).breakpointManager
            .addLineBreakpoint(type(), file.url, line, null, info)
    }

    private fun type() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!
}
