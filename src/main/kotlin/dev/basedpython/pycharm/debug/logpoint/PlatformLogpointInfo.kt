package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo
import java.lang.reflect.Method

/**
 * The description a log point is created from, filled in by whichever setter the running IDE has.
 *
 * ## why the expression is not set by a compiled call
 *
 * [XLineBreakpointAdditionalInfo] is how the platform is told, at the moment a line breakpoint is
 * added, that it logs rather than stops — `XBreakpointManagerImpl.addLineBreakpoint` reads the log
 * expression off it and turns logging on. Its builder's one interesting setter changed type between
 * 262 and 263:
 *
 * | build | setter |
 * | --- | --- |
 * | 262 | `setLogExpressionIfEnabled(String)`, which the platform makes a plain-text expression of |
 * | 263 | `setLogExpressionIfEnabled(XExpression)`, which it keeps as it is given |
 *
 * Both read off `intellij.platform.debugger.jar` in the two IDEs rather than guessed. This plugin is
 * one artifact declaring 262 to 263.*, so a compiled call to either is a `NoSuchMethodError` on half
 * of it, and it was: taking the `print` quick fix on a 2026.3 EAP produced
 * *'…Builder.setLogExpressionIfEnabled(java.lang.String)'* out of `ReplaceWithLogpointFix.applyFix`,
 * with the `print` call already deleted and no log point put in its place.
 *
 * The name is the same in both, so this asks for the setter by parameter type — the thing that
 * actually differs — and passes the expression the way that build wants it.
 *
 * ## why a failure here is not thrown
 *
 * Every caller creates a log point as part of something the user just did, and one of them has
 * already edited the document by the time it gets here. A build with neither setter still gets its
 * log point: the callers restate the expression on the breakpoint itself once it exists, which is
 * what attaches the basedpython language to it in any case. What is lost is the platform's own
 * `setLogMessage(true)` on 262, so this says so in the log rather than passing silently.
 */
internal object PlatformLogpointInfo {
    private val LOG = Logger.getInstance(PlatformLogpointInfo::class.java)

    /**
     * The info for a log point that logs [expression] and suspends by [suspendPolicy].
     *
     * [expression] is null for a log point with nothing to log yet — the one *Add Log Point* makes,
     * which is filled in by typing in its box.
     *
     * No `setVerticalPlacement` here any more. That setter, and the placement enum it takes, are
     * `@ApiStatus.Internal`; a log point is now marked by [dev.basedpython.pycharm.debug.ByBreakpointProperties]
     * instead, and drawn by an inlay this plugin owns. See docs/internal-api.md.
     */
    fun of(
        suspendPolicy: SuspendPolicy,
        expression: XExpression? = null,
    ): XLineBreakpointAdditionalInfo {
        val builder = XLineBreakpointAdditionalInfo.Builder()
            .setSuspendPolicy(suspendPolicy)
        if (expression != null) builder.carry(expression)
        return builder.build()
    }

    /** Puts [expression] on this builder, in whichever of the two shapes the running IDE has. */
    private fun XLineBreakpointAdditionalInfo.Builder.carry(expression: XExpression) {
        val builder = XLineBreakpointAdditionalInfo.Builder::class.java
        try {
            builder.methodOrNull(SETTER, XExpression::class.java)?.let { return it.invokeOn(this, expression) }
            builder.methodOrNull(SETTER, String::class.java)?.let { return it.invokeOn(this, expression.expression) }
        } catch (e: ReflectiveOperationException) {
            LOG.error("the log point's expression would not go on ${builder.name}", e)
            return
        } catch (e: LinkageError) {
            // The same family as the `NoSuchMethodError` this object exists for — the method
            // resolved but something it names no longer does. Caught for the same reason, and not a
            // wider `Throwable`, which would swallow a cancellation.
            LOG.error("the log point's expression would not link against ${builder.name}", e)
            return
        }

        LOG.error(
            "${builder.name} has neither $SETTER this plugin knows " +
                "(${builder.methods.filter { it.name == SETTER }.joinToString { it.toGenericString() }}); " +
                "the log point is created without its expression and gets it back afterwards",
        )
    }

    private fun Class<*>.methodOrNull(name: String, vararg types: Class<*>): Method? =
        try {
            getMethod(name, *types)
        } catch (_: NoSuchMethodException) {
            null
        }

    private fun Method.invokeOn(builder: XLineBreakpointAdditionalInfo.Builder, argument: Any) {
        invoke(builder, argument)
    }

    private const val SETTER = "setLogExpressionIfEnabled"
}
