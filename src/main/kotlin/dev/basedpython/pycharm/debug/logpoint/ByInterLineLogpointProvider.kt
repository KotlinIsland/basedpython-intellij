package dev.basedpython.pycharm.debug.logpoint

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.InterLineBreakpointConfiguration
import com.intellij.openapi.editor.impl.InterLineBreakpointConfigurationProvider
import com.intellij.openapi.editor.impl.InterLineBreakpointProperties
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.IconUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByBreakpointFiles
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Puts the "Add Log" affordance in the gutter gap between two lines of a `.by` file — hover between
 * the line numbers, click, and a log point appears where you clicked.
 *
 * Everything that gesture needs already exists in the platform, in every IDE this plugin runs in:
 * `EditorGutterComponentImpl` paints whatever this extension describes and reserves the hit area for
 * it, `EditorUtil.yToLogicalLineWithInterLineDetection` turns a click there into a
 * `BreakpointArea.InterLine`, and `XLineBreakpointManager`'s mouse listener performs the ordinary
 * ToggleLineBreakpoint action with these [InterLineBreakpointProperties] in its data context —
 * which is where `isLogging` becomes a non-suspending breakpoint carrying a log expression. What is
 * *not* in the platform is anything registering this extension: the only implementation ships in
 * `intellij.debugger.logpoints.frontend`, a module bundled with IntelliJ IDEA's Java plugin, so
 * PyCharm has the whole mechanism and nothing that switches it on. This is that switch.
 *
 * An inter-line breakpoint is anchored to the line *below* the gap (`BreakpointArea.from` builds
 * `InterLine(line)` for the gap above `line`), and that is the line it binds to at runtime: the log
 * runs just before that line does.
 *
 * Two things have to be true before any of this is visible, and both are easy to be caught by. The
 * gap is only a place you can click when breakpoints live *over* the line numbers, which is a UI
 * setting; and by default this stands aside in IntelliJ IDEA, which is the IDE `runIde` starts. See
 * [ByLogpoints.pluginOwnsLogpoints].
 */
class ByInterLineLogpointProvider : InterLineBreakpointConfigurationProvider {

    override val uniqueId: String = ID

    override fun getConfiguration(editor: Editor): Flow<InterLineBreakpointConfiguration> {
        val outcome = decline(editor)
        // Logged rather than silent, because every way this declines is invisible: the gutter simply
        // has nothing between the lines, which looks exactly like a feature that was never built.
        // One line per editor, at INFO, so `idea.log` answers "why is there no gap" on its own.
        LOG.info("inter-line log point gap for ${editor.virtualFile?.name ?: "<no file>"}: ${outcome ?: "offered"}")
        if (outcome != null) return emptyFlow()
        return offer(editor)
    }

    /** Why this editor gets no gap, or null when it gets one. */
    private fun decline(editor: Editor): String? = when {
        !ByLogpoints.pluginProvidesLogpointUi() ->
            "declined: the IDE provides log points itself (or basedpython.logpoints.provider says so)"
        !EditorUtil.isBreakPointsOnLineNumbers() ->
            "declined: breakpoints are not over the line numbers (gutter right-click | Appearance | " +
                "Breakpoints Over Line Numbers), or the IDE is in presentation or distraction-free mode"
        editor.project == null -> "declined: editor has no project"
        fileOf(editor) == null -> "declined: no file behind the document"
        !ByBreakpointFiles.accepts(fileOf(editor)) ->
            "declined: not a basedpython file (${fileOf(editor)!!.name})"
        XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java) == null ->
            "declined: the basedpython line breakpoint type is not registered"
        else -> null
    }

    private fun fileOf(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)

    private fun offer(editor: Editor): Flow<InterLineBreakpointConfiguration> {
        val project = editor.project ?: return emptyFlow()
        val file = fileOf(editor) ?: return emptyFlow()
        val gutter = editor.gutter as? EditorGutterComponentEx
        return flowOf(
            InterLineBreakpointConfiguration(
                // The gap is shorter than a line, which is why the platform's own logpoint provider
                // scales the same icon by the same factor rather than drawing it full size.
                icon = IconUtil.scale(AllIcons.Debugger.Db_no_suspend_breakpoint, gutter, ICON_SCALE),
                hoverTooltip = TOOLTIP,
                breakpointProperties = InterLineBreakpointProperties(isLogging = true),
                // Not optional, whatever the signature's default says: without it the gutter opens
                // no gap at all. See ByInterLineShift.
                animator = animatorFor(gutter),
                availableFor = { line -> canAddLogpoint(project, file, line) },
            )
        )
    }

    /**
     * Whether the gap is free at [line] — and the probe that says whether the platform is asking.
     *
     * "offered" only proves the configuration was built. This is called while the gutter works out
     * what the mouse is over, so it is the first point at which the platform has *used* it: if these
     * lines never appear while hovering between two line numbers, the failure is upstream of
     * anything this class can control, and no amount of correctness here would show a gap.
     *
     * Rate-limited because it runs on mouse movement.
     */
    private fun logAvailability(line: Int, available: Boolean) {
        if (probes.incrementAndGet() <= MAX_PROBES) {
            LOG.info("inter-line log point gap consulted for line $line: available=$available")
        }
    }

    /**
     * One shift per gutter, kept on the component: the gutter holds on to whichever animator it was
     * last given and calls `stopShift` on it, so handing it a fresh one per configuration would
     * leave the old one holding a shift nothing will ever clear.
     */
    private fun animatorFor(gutter: EditorGutterComponentEx?): ByInterLineShift? {
        if (gutter == null) return null
        (gutter.getClientProperty(SHIFT_KEY) as? ByInterLineShift)?.let { return it }
        return ByInterLineShift(gutter).also { gutter.putClientProperty(SHIFT_KEY, it) }
    }

    /** False once a log point is already in this gap — offering to add a second one is noise. */
    private fun canAddLogpoint(project: Project, file: VirtualFile, line: Int): Boolean {
        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)
        val available = type != null && XDebuggerManager.getInstance(project).breakpointManager
            .findBreakpointsAtLine(type, file, line, XLineBreakpointVerticalPlacement.INTER_LINE)
            .isEmpty()
        logAvailability(line, available)
        return available
    }

    private companion object {
        val LOG = Logger.getInstance(ByInterLineLogpointProvider::class.java)
        const val ID = "basedpython-logpoint"
        const val TOOLTIP = "Add Log"
        const val ICON_SCALE = 0.7f
        const val SHIFT_KEY = "basedpython.logpoint.interLineShift"
        const val MAX_PROBES = 40
        val probes = java.util.concurrent.atomic.AtomicInteger()
    }
}
