package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
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
 * The IDE's own logpoints feature ships in modules bundled with IntelliJ IDEA's Java plugin. Where
 * it is loaded it does all of this better — the gutter gap, the inline editor, the caret bridging —
 * so everything in this package stands down and lets it. Where it is not loaded, which is every
 * other IDE this plugin runs in, none of it exists and this package is the whole feature.
 */
object ByLogpoints {

    /**
     * Whether this plugin provides the log point UI — the gutter gap, the inline `Log:` field and
     * *Add Log Point* — rather than leaving it to the IDE's own.
     *
     * One rule for all of it: use the IDE's where it has one, and stand in for it exactly where it
     * does not. The pieces cannot be mixed. Two gaps is a coin flip, since
     * `InterLineBreakpointConfigurationProvider.findFirstConfiguration` collects providers into a
     * map keyed by id and takes the first available for the line — `order=` decides nothing — and
     * two prompts is two fields over one log point, which is what forcing this on in IDEA looked
     * like on screen.
     *
     * The IDE's own is IntelliJ IDEA's, and only IDEA's: the modules are bundled with its Java
     * plugin and `intellij.debugger.logpoints.backend` depends on `intellij.java.debugger.impl`, so
     * this is not an optional dependency PyCharm forgot but a feature built on the JVM debugger.
     * PyCharm Professional, which this plugin also ships to, has none of it.
     *
     * There is deliberately no way to force this on where the IDE has its own. The setting used to
     * offer one, for want of any other way to see this code in the IDE `runIde` starts, and it did
     * exactly what it said: two gaps and two boxes, one of them the good one. `./gradlew runPyCharm`
     * is how to look at this now, and `ide` — the one direction that cannot produce a duplicate —
     * is all that is left of the override.
     */
    fun pluginProvidesLogpointUi(): Boolean = preference() != "ide" && !ideHasLogpoints()

    /**
     * Whether the IDE ships the logpoints feature itself.
     *
     * Asked of the extension area rather than by loading a class: the logpoints modules are internal
     * to the Java plugin, and this extension point is the thing they register that matters here.
     */
    private fun ideHasLogpoints(): Boolean =
        ApplicationManager.getApplication().extensionArea.hasExtensionPoint(LOGPOINTS_EP)

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
     * basedpython in the box and in the breakpoint dialog rather than as plain text, and what the
     * platform loses on 262, where the builder that creates a log point takes the expression as a
     * `String` and makes a plain-text expression of it (see [PlatformLogpointInfo]).
     */
    fun expressionOf(text: String): XExpression = XDebuggerUtil.getInstance()
        .createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)

    /** A log point with nothing to log — freshly created by a click in the gutter gap, and useless until typed into. */
    fun isUnfilled(breakpoint: XLineBreakpoint<*>): Boolean =
        breakpoint.logExpressionObject?.expression.isNullOrBlank()

    private const val LOGPOINTS_EP = "com.intellij.xdebugger.logpoints.editorsProviderFactory"
    private const val PROVIDER_KEY = "basedpython.logpoints.provider"
}
