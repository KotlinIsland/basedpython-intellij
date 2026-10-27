package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapSourceBreakpoint
import com.intellij.platform.dap.SourcePosition
import com.intellij.platform.dap.TextPosition
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint

/**
 * Sends a `.by` line breakpoint to the adapter, with a DAP `logMessage` only where the adapter can do
 * all that the breakpoint asks: log one expression and carry on.
 *
 * The platform's handler turns any breakpoint with an expression to log into a DAP log point, which
 * the adapter logs without ever stopping — so the IDE never hears of the hit, and whatever else the
 * breakpoint said was dropped: *"Breakpoint hit" message*, *Stack trace*, a suspend policy, *Remove
 * once hit*. Measured under bpd: message and expression printed the expression alone; message, stack
 * and expression the same. Everywhere [adapterLogMessage] says no, the breakpoint goes as an ordinary
 * one, the adapter stops, and the IDE does what it does for any language: evaluates the expression,
 * prints the message, the expression and the stack in that order, and resumes unless the breakpoint
 * suspends — PyCharm's own rule for debugpy (`isLogpointForDebugpy`).
 *
 * Replaces the platform's handler for [ByLineBreakpointType] in [ByDapXDebugProcess], as PyCharm's
 * Python debug process replaces it for its own type; which breakpoints are sent, where, and with which
 * condition is unchanged — the line and condition the platform's handler sends.
 */
internal class ByDapSourceBreakpointHandler(
    private val dapSession: DapDebugSession,
    private val session: XDebugSession,
) : XBreakpointHandler<XLineBreakpoint<ByBreakpointProperties>>(ByLineBreakpointType::class.java) {

    /** Each breakpoint as the adapter was given it; read and written only on the session's own executor. */
    private val sent = HashMap<XLineBreakpoint<ByBreakpointProperties>, DapSourceBreakpoint>()

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<ByBreakpointProperties>) {
        val file = breakpoint.sourcePosition?.file ?: return
        // DAP's lines count from 1; a `.by` breakpoint is a whole line, which column 0 says
        val position = SourcePosition(file, TextPosition(breakpoint.line + 1, 0))
        dapSession.post {
            val condition = breakpoint.conditionExpression?.expression
            val dapBreakpoint = ByDapSourceBreakpoint(DapSourceBreakpoint.create(session, position, condition, breakpoint))
            sent[breakpoint] = dapBreakpoint
            with(dapSession.breakpointManager) { addSourceBreakpoint(dapBreakpoint) }
        }
    }

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<ByBreakpointProperties>, temporary: Boolean) {
        dapSession.post {
            val dapBreakpoint = sent.remove(breakpoint) ?: return@post
            with(dapSession.breakpointManager) { removeSourceBreakpoint(dapBreakpoint) }
        }
    }
}

/** The platform's [DapSourceBreakpoint] for a breakpoint, with the log message [adapterLogMessage] gives. */
private class ByDapSourceBreakpoint(private val delegate: DapSourceBreakpoint) : DapSourceBreakpoint by delegate {

    override val logMessage: String? get() = ideBreakpoint?.let(::adapterLogMessage)

    override fun equals(other: Any?): Boolean = other is ByDapSourceBreakpoint && other.delegate == delegate

    override fun hashCode(): Int = delegate.hashCode()
}

/**
 * The DAP `logMessage` [breakpoint] is sent with: its expression, `{expression}`, when logging it is
 * all the breakpoint does — it does not suspend, logs neither the hit nor the stack and is not removed
 * once hit — and the expression can be interpolated whole: one line, with no braces of its own, which
 * a log message reads as the end of the expression or as a literal brace. Otherwise null, and the
 * adapter stops for the IDE to log.
 */
internal fun adapterLogMessage(breakpoint: XLineBreakpoint<*>): String? {
    val expression = breakpoint.logExpressionObject?.expression ?: return null
    val onlyLogs = breakpoint.suspendPolicy == SuspendPolicy.NONE &&
        !breakpoint.isLogMessage && !breakpoint.isLogStack && !breakpoint.isTemporary
    if (!onlyLogs) return null
    if (expression.isBlank() || expression.any { it == '\n' || it == '\r' || it == '{' || it == '}' }) return null
    return "{$expression}"
}
