package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import dev.basedpython.pycharm.util.BasedPythonBundle
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
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
     * Records [breakpoint] as part of the command in progress, so <kbd>Ctrl+Z</kbd> takes it back.
     *
     * Joins the command when there is one — that is the `print` quick fix, where the deleted line
     * and the log point have to travel as a pair, and joining is the whole point.
     *
     * Opens one when there is not, which is every gutter route: *Add Logging Breakpoint…* from the
     * gutter menu, and IntelliJ IDEA's own click in the gutter gap, neither of which runs in a
     * command at all — which is exactly why neither could be undone. A command of our own gives the
     * log point an undo step of its own, which is what the user is reaching for.
     */
    fun record(project: Project, document: Document, breakpoint: XLineBreakpoint<*>) {
        val file = breakpoint.sourcePosition?.file ?: return
        val state = Recreate(
            file = file,
            line = breakpoint.line,
            suspendPolicy = breakpoint.suspendPolicy,
            expression = breakpoint.logExpressionObject?.expression,
        )
        val action = object : BasicUndoableAction(document) {
            override fun undo() = state.remove(project)
            override fun redo() = state.add(project)
        }
        val commands = CommandProcessor.getInstance()
        if (commands.currentCommand != null) {
            UndoManager.getInstance(project).undoableActionPerformed(action)
            return
        }
        commands.executeCommand(
            project,
            { UndoManager.getInstance(project).undoableActionPerformed(action) },
            BasedPythonBundle.message("debug.logpoint.undo.add"),
            null,
            UndoConfirmationPolicy.DEFAULT,
            document,
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
        val suspendPolicy: SuspendPolicy,
        val expression: String?,
    ) {
        fun remove(project: Project) {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val type = type() ?: return
            // The placement-filtered overload is @ApiStatus.Internal; filter on our own flag
            // instead, so undo removes the log point it recorded and never a plain breakpoint
            // somebody put on the same line.
            manager.findBreakpointsAtLine(type, file, line)
                .filter { ByLogpoints.asLogpoint(it) != null }
                .forEach { manager.removeBreakpoint(it) }
        }

        fun add(project: Project) {
            val type = type() ?: return
            val logged = expression?.let { ByLogpoints.expressionOf(it) }
            val info = PlatformLogpointInfo.of(suspendPolicy, logged)
            val breakpoint = XDebuggerManager.getInstance(project).breakpointManager
                .addLineBreakpoint(type, file.url, line, ByLogpoints.logpointProperties(), info)
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
