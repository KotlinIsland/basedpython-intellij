package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry
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
     * Whether to put the "Add Log" affordance in the gutter gap of `.by` files.
     *
     * Yes by default, in every IDE, including IntelliJ IDEA where a provider of its own is also
     * registered. This used to stand aside there on the reasoning that IDEA's implementation is the
     * better one — it is — but a better implementation that does not appear in `.by` files is worse
     * than a plainer one that does, and standing aside meant the feature simply did not exist in the
     * IDE `runIde` starts.
     *
     * Two live providers is not a tie that can be won on purpose:
     * `InterLineBreakpointConfigurationProvider.findFirstConfiguration` collects every provider's
     * flow into a map keyed by its id and takes the first entry available for the line, so the
     * winner is hash order and the `order=` attribute decides nothing. That is survivable here only
     * because the two gaps do the same thing: the click goes through the platform's own toggle
     * either way and produces the same `ByLineBreakpointType` breakpoint. What must not double up is
     * the field that opens afterwards — see [pluginOwnsLogpointPrompt].
     */
    fun pluginDrawsGap(): Boolean = preference() != "ide"

    /**
     * Whether *this* plugin opens the inline expression field, rather than the IDE's own prompt.
     *
     * This one does defer, because two prompts would mean two fields over one log point. Where the
     * IDE ships logpoints its prompt is already listening and is the better of the two, and now that
     * `.by` breakpoints carry an `XDebuggerEditorsProvider` it has what it needs to open on one.
     */
    fun pluginOwnsLogpointPrompt(): Boolean = when (preference()) {
        "plugin" -> true
        "ide" -> false
        else -> !ideHasLogpoints()
    }

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
    private const val PROVIDER_KEY = "basedpython.logpoints.provider"
}
