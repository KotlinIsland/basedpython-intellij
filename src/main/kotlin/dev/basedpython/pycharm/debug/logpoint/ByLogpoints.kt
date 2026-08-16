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
     */
    fun pluginProvidesLogpointUi(): Boolean = when (preference()) {
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
