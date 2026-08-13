package dev.basedpython.pycharm.debug.logpoint

import com.intellij.icons.AllIcons
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
        // In IntelliJ IDEA the Java plugin's own provider is registered and is the better one — it
        // brings the inline editor with it. Only the first configuration found is used, so standing
        // aside is the difference between adding the gap editor and replacing it with a worse one.
        if (!ByLogpoints.pluginOwnsLogpoints()) return emptyFlow()

        // The gap only exists when breakpoints live over the line numbers — *Settings | Editor |
        // General | Appearance | Show breakpoints over line numbers*, and off in presentation and
        // distraction-free mode. With them beside the numbers there is no gutter row between two
        // lines to click, so the platform reserves no hit area and this would describe an
        // affordance that never appears. The IDE's own provider asks the same question.
        if (!EditorUtil.isBreakPointsOnLineNumbers()) return emptyFlow()

        val project = editor.project ?: return emptyFlow()
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return emptyFlow()
        if (!file.extension.equals("by", ignoreCase = true)) return emptyFlow()

        return flowOf(
            InterLineBreakpointConfiguration(
                // The gap is shorter than a line, which is why the platform's own logpoint provider
                // scales the same icon by the same factor rather than drawing it full size.
                icon = IconUtil.scale(
                    AllIcons.Debugger.Db_no_suspend_breakpoint,
                    editor.gutter as? EditorGutterComponentEx,
                    ICON_SCALE,
                ),
                hoverTooltip = TOOLTIP,
                breakpointProperties = InterLineBreakpointProperties(isLogging = true),
                availableFor = { line -> canAddLogpoint(project, file, line) },
            )
        )
    }

    /** False once a log point is already in this gap — offering to add a second one is noise. */
    private fun canAddLogpoint(project: Project, file: VirtualFile, line: Int): Boolean {
        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java) ?: return false
        return XDebuggerManager.getInstance(project).breakpointManager
            .findBreakpointsAtLine(type, file, line, XLineBreakpointVerticalPlacement.INTER_LINE)
            .isEmpty()
    }

    private companion object {
        const val ID = "basedpython-logpoint"
        const val TOOLTIP = "Add Log"
        const val ICON_SCALE = 0.7f
    }
}
