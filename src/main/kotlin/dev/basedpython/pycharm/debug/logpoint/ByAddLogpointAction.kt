package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.debug.ByLineBreakpointType

/**
 * Adds a log point above the caret's line, or opens the one already there — the keyboard's way in to
 * the gutter gap.
 *
 * Shares `Ctrl+Alt+F8` with the IDE's own *Add Logpoint* (IntelliJ IDEA's, bundled with PyCharm
 * since 263.7355), and gives way to it exactly where it would act: this action reports itself
 * disabled when the IDE's is enabled for the same event, and the action system passes the keystroke
 * to the enabled one. Asked of the IDE's action itself rather than of whether the IDE ships one —
 * which is what this used to ask, and which left both disabled in every `.by` editor once PyCharm
 * shipped it, since the IDE's toggles only breakpoint types that support inter-line placement and
 * `basedpython-line` cannot say it does (see [ByLogpoints]).
 *
 * "Above the caret's line" and "anchored to the caret's line" are the same statement — an inter-line
 * breakpoint belongs to the line below its gap.
 */
class ByAddLogpointAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            ByLogpoints.pluginProvidesLogpointUi() && target(e) != null && !ideActionApplies(e)
    }

    /**
     * Whether the IDE's own *Add Logpoint* is enabled for [e] — its own update, run on a copy of its
     * presentation, so nothing on screen changes.
     */
    private fun ideActionApplies(e: AnActionEvent): Boolean {
        val ide = ActionManager.getInstance().getAction(IDE_ACTION_ID) ?: return false
        val event = AnActionEvent.createEvent(
            e.dataContext,
            ide.templatePresentation.clone(),
            e.place,
            e.uiKind,
            e.inputEvent,
        )
        ActionUtil.updateAction(ide, event)
        return event.presentation.isEnabled
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
        if (!BasedPythonSources.isOwnedSource(file)) return null
        return Target(project, editor, file, editor.caretModel.logicalPosition.line)
    }

    /**
     * The log point already on [line], if there is one.
     *
     * The placement-filtered `findBreakpointsAtLine` overload is `@ApiStatus.Internal`; the
     * three-argument one is not, so the filtering that told log points from ordinary breakpoints
     * happens here instead, against the same flag that defines one.
     */
    private fun existing(project: Project, file: VirtualFile, line: Int): XLineBreakpoint<*>? =
        XDebuggerManager.getInstance(project).breakpointManager
            .findBreakpointsAtLine(type(), file, line)
            .firstOrNull { ByLogpoints.asLogpoint(it) != null }

    private fun create(project: Project, file: VirtualFile, line: Int): XLineBreakpoint<*> {
        val info = PlatformLogpointInfo.of(SuspendPolicy.NONE)
        return XDebuggerManager.getInstance(project).breakpointManager
            .addLineBreakpoint(type(), file.url, line, ByLogpoints.logpointProperties(), info)
    }

    private fun type() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    private companion object {
        /** The IDE's own *Add Logpoint*, from `intellij.debugger.logpoints.frontend`. */
        const val IDE_ACTION_ID = "AddLogpoint"
    }
}
