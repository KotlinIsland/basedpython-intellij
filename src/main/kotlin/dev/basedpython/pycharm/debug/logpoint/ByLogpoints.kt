package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.util.registry.Registry
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import dev.basedpython.pycharm.debug.ByBreakpointProperties
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.lang.BasedPythonLanguage

/**
 * What counts as a log point here, and whose job it is to draw one.
 *
 * IntelliJ IDEA's own logpoints feature — bundled with PyCharm too since 263.7355 — serves only
 * breakpoint types that support inter-line placement: its `canBeLogpoint`, which decides whether
 * it draws a log point's box, asks `type.supportsInterLinePlacement()` first, and its *Add
 * Logpoint* toggles only types that say yes. That switch is `@ApiStatus.Internal` (see
 * docs/internal-api.md), so `basedpython-line` keeps the default, no, and none of the IDE's
 * log point UI ever applies to a `.by` breakpoint. Measured in PyCharm 263.7471: a `.by` log point
 * got no box from either side while this package stood down for the IDE's, and both *Add Logpoint*
 * and *Add Log Point* were disabled in a `.by` editor.
 *
 * So this package is the whole feature for `.by` in every IDE, and gives way only where the IDE's
 * would actually act — see [ByAddLogpointAction].
 */
object ByLogpoints {

    /**
     * Whether this plugin draws the log point UI — the inline `Log:` field and *Add Log Point*.
     *
     * Always, unless the registry says `ide`. There is no IDE whose own box serves a `.by` log
     * point (see the class comment), so the box cannot be drawn twice; the one place the IDE could
     * take over, the shortcut, is decided per keystroke by [ByAddLogpointAction].
     */
    fun pluginProvidesLogpointUi(): Boolean = preference() != "ide"

    /**
     * Read defensively: this is consulted while the gutter paints, and a registry key that failed to
     * register would otherwise throw once per frame rather than fall back to the default.
     */
    private fun preference(): String =
        runCatching { Registry.get(PROVIDER_KEY).selectedOption }.getOrNull() ?: "auto"

    /**
     * A `.by` breakpoint that logs rather than stopping on its line.
     *
     * Two ways to be one, because there are two ways to make one.
     *
     * The first is this plugin's own [ByBreakpointProperties.isLogpoint], set wherever this plugin
     * creates a log point — *Add Log Point*, the `print` quick fix — and persisted with the
     * breakpoint. It used to be asked of the platform instead (`placement ==
     * XLineBreakpointVerticalPlacement.INTER_LINE`, true of a breakpoint the gutter gap created),
     * and both the getter and the enum are `@ApiStatus.Internal`; see docs/internal-api.md. It is
     * also what carries a log point that has *nothing to log yet* — one freshly created by
     * <kbd>Ctrl+Alt+F8</kbd>, which is a log point the moment it exists and not once it is typed in.
     *
     * The second is the breakpoint simply behaving like one: it does not suspend, it logs an
     * expression, it is not temporary, and it covers a whole line rather than part of one. That is
     * IntelliJ IDEA's own definition — `XLogpointUtilsKt.canBeLogpoint`, which is what decides
     * whether *it* draws a log point — restated in public API (`getLogExpressionObject` already
     * folds in `isLogExpressionEnabled`, which is only on the internal proxy). Without it, *Add
     * Logging Breakpoint…* from the gutter — the platform's own `AddLineBreakpointAction.WithLogging`,
     * which makes an ordinary breakpoint and then turns logging on and suspending off — produced a
     * log point in every respect that got no `Log:` box, because nothing in that path knows to set a
     * property belonging to this plugin.
     */
    fun asLogpoint(breakpoint: XBreakpoint<*>): XLineBreakpoint<*>? {
        val line = breakpoint as? XLineBreakpoint<*> ?: return null
        val type = line.type as? ByLineBreakpointType ?: return null
        if ((line.properties as? ByBreakpointProperties)?.isLogpoint == true) return line
        return if (logsInsteadOfSuspending(type, line)) line else null
    }

    /** IntelliJ IDEA's `canBeLogpoint`, in the API a plugin is allowed to use. */
    private fun logsInsteadOfSuspending(type: ByLineBreakpointType, breakpoint: XLineBreakpoint<*>): Boolean {
        if (breakpoint.suspendPolicy != SuspendPolicy.NONE) return false
        if (breakpoint.isTemporary) return false
        if (breakpoint.logExpressionObject == null) return false
        // A breakpoint on part of a line — one of several lambdas, say — is not a statement's worth
        // of gap, and IDEA declines to draw one there too. `.by` has no such breakpoints today; this
        // is the rule, not a workaround.
        @Suppress("UNCHECKED_CAST")
        return type.getHighlightRange(breakpoint as XLineBreakpoint<ByBreakpointProperties>) == null
    }

    /** The properties a newly created log point carries. */
    fun logpointProperties(): ByBreakpointProperties =
        ByBreakpointProperties().apply { isLogpoint = true }

    /**
     * [text] as the expression a `.by` log point logs.
     *
     * With the language attached, which is not decoration: it is what makes the expression edit as
     * basedpython in the box and in the breakpoint dialog rather than as plain text.
     */
    fun expressionOf(text: String): XExpression = XDebuggerUtil.getInstance()
        .createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)

    /** A log point with nothing to log — freshly created by a click in the gutter gap, and useless until typed into. */
    fun isUnfilled(breakpoint: XLineBreakpoint<*>): Boolean =
        breakpoint.logExpressionObject?.expression.isNullOrBlank()

    private const val PROVIDER_KEY = "basedpython.logpoints.provider"
}
