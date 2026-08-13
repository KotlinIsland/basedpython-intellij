package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByLineBreakpointType

/**
 * What counts as a log point here, and whose job it is to draw one.
 *
 * The IDE's own logpoints feature ships in modules bundled with IntelliJ IDEA's Java plugin. Where
 * it is loaded it does all of this better — the gutter gap, the inline editor, the caret bridging —
 * so everything in this package stands down and lets it. Where it is not loaded, which is every
 * other IDE this plugin runs in, none of it exists and this package is the whole feature.
 */
object ByLogpoints {

    /**
     * Whether this plugin should provide the log point UI, rather than defer to the IDE's.
     *
     * Asked of the extension area rather than by loading a class: the logpoints modules are internal
     * to the Java plugin, and this extension point is the thing they register that matters here.
     */
    fun pluginOwnsLogpoints(): Boolean =
        !ApplicationManager.getApplication().extensionArea.hasExtensionPoint(LOGPOINTS_EP)

    /**
     * A `.by` breakpoint that logs in a gap rather than stopping on a line.
     *
     * The vertical placement is part of the identity, not decoration: it is what the gutter drew and
     * what the inline editor positions itself against.
     */
    fun asLogpoint(breakpoint: XBreakpoint<*>): XLineBreakpoint<*>? {
        val line = breakpoint as? XLineBreakpoint<*> ?: return null
        if (line.type !is ByLineBreakpointType) return null
        if (line.placement != XLineBreakpointVerticalPlacement.INTER_LINE) return null
        return line
    }

    /** A log point with nothing to log — freshly created by a click in the gutter gap, and useless until typed into. */
    fun isUnfilled(breakpoint: XLineBreakpoint<*>): Boolean =
        breakpoint.logExpressionObject?.expression.isNullOrBlank()

    private const val LOGPOINTS_EP = "com.intellij.xdebugger.logpoints.editorsProviderFactory"
}
