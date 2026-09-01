package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.debug.ByLineBreakpointType

/**
 * Makes creating a log point part of whatever the user just did, so Ctrl+Z takes it back.
 *
 * A breakpoint is not document state, so nothing about it is undoable on its own — there is no
 * breakpoint undo anywhere in the platform (IntelliJ IDEA's lives in
 * `intellij.debugger.logpoints.backend`, which depends on the Java debugger). Left alone, the
 * `print` quick fix produced the worst possible half of that: the deleted line came back on undo and
 * the log point stayed, so the program logged the value twice.
 *
 * Registering against the document is what ties the two together. The platform groups everything in
 * one command into one undo step, so the text edit and this travel as a pair, in both directions.
 */
object ByLogpointUndo {

    /**
     * Records [breakpoint] as part of the command in progress. Does nothing outside a command —
     * there would be no undo step to join, and the platform would refuse it.
     */
    fun record(project: Project, document: Document, breakpoint: XLineBreakpoint<*>) {
        if (CommandProcessor.getInstance().currentCommand == null) return
        val file = breakpoint.sourcePosition?.file ?: return
        val state = Recreate(
            file = file,
            line = breakpoint.line,
            placement = breakpoint.placement,
            suspendPolicy = breakpoint.suspendPolicy,
            expression = breakpoint.logExpressionObject?.expression,
        )
        UndoManager.getInstance(project).undoableActionPerformed(
            object : BasicUndoableAction(document) {
                override fun undo() = state.remove(project)
                override fun redo() = state.add(project)
            }
        )
    }

    /**
     * Everything needed to put the log point back, held as values rather than as the breakpoint.
     *
     * Undo removes the object, so redo cannot reuse it — and a breakpoint restored from its
     * description is indistinguishable from the original, since a line breakpoint *is* its file,
     * line and settings.
     */
    private class Recreate(
        val file: VirtualFile,
        val line: Int,
        val placement: XLineBreakpointVerticalPlacement,
        val suspendPolicy: SuspendPolicy,
        val expression: String?,
    ) {
        fun remove(project: Project) {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val type = type() ?: return
            manager.findBreakpointsAtLine(type, file, line, placement)
                .toList()
                .forEach { manager.removeBreakpoint(it) }
        }

        fun add(project: Project) {
            val type = type() ?: return
            val logged = expression?.let { ByLogpoints.expressionOf(it) }
            val info = PlatformLogpointInfo.of(placement, suspendPolicy, logged)
            val breakpoint = XDebuggerManager.getInstance(project).breakpointManager
                .addLineBreakpoint(type, file.url, line, null, info)
            // Restated on the breakpoint, because what the info carries is not always what comes
            // back out of it: on 262 the platform takes the expression as text and rebuilds it as a
            // plain-text one, and redo would quietly hand back a log point that no longer edits as
            // basedpython. It is also what puts the expression there at all on a build whose builder
            // this plugin cannot fill in — see PlatformLogpointInfo.
            logged?.let { breakpoint.logExpressionObject = it }
        }

        private fun type() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)
    }
}
