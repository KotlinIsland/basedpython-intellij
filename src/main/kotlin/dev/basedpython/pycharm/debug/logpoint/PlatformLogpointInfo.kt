package dev.basedpython.pycharm.debug.logpoint

import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo

/**
 * The description a log point is created from.
 *
 * [XLineBreakpointAdditionalInfo] is how the platform is told, at the moment a line breakpoint is
 * added, that it logs rather than stops — `XBreakpointManagerImpl.addLineBreakpoint` reads the log
 * expression off it and turns logging on. The expression goes on as the [XExpression] it is, so the
 * language attached to it — what makes it edit as basedpython in the box and in the breakpoint
 * dialog — is kept.
 */
internal object PlatformLogpointInfo {

    /**
     * The info for a log point that logs [expression] and suspends by [suspendPolicy].
     *
     * [expression] is null for a log point with nothing to log yet — the one *Add Log Point* makes,
     * which is filled in by typing in its box.
     *
     * No `setVerticalPlacement` here. That setter, and the placement enum it takes, are
     * `@ApiStatus.Internal`; a log point is marked by [dev.basedpython.pycharm.debug.ByBreakpointProperties]
     * instead, and drawn by an inlay this plugin owns. See docs/internal-api.md.
     */
    fun of(
        suspendPolicy: SuspendPolicy,
        expression: XExpression? = null,
    ): XLineBreakpointAdditionalInfo {
        val builder = XLineBreakpointAdditionalInfo.Builder()
            .setSuspendPolicy(suspendPolicy)
        if (expression != null) builder.setLogExpressionIfEnabled(expression)
        return builder.build()
    }
}
