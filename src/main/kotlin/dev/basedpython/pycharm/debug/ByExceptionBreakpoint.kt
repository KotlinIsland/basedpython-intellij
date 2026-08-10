package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.ui.XBreakpointCustomPropertiesPanel
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * Which exceptions a basedpython exception breakpoint stops on.
 *
 * Both flags are pydevd filter ids, and both were checked against a live session before being
 * offered here.
 *
 * [notifyOnTerminate] (`uncaught`) stops only when nothing will handle the exception. It is the
 * default, and it is genuinely reachable despite basedpython's checked exceptions: the compiler
 * rejects `error[unhandled-exception]: ValueError can escape main` outright, but it does not model
 * everything — a `KeyError` from a dict lookup compiles happily and stops here at runtime.
 *
 * [notifyOnRaise] (`raised`) stops the moment an exception is constructed, including ones that are
 * about to be caught. That is the only way to see a swallowed exception, and too noisy to be a
 * default: a program that deliberately catches a `JSONDecodeError` stops on it too.
 *
 * There is deliberately no "ignore library code" option. pydevd spells it as a `:ignoreLibraries`
 * suffix, and with it the breakpoint never fires at all here — the transpiled program lives in a
 * temp directory that pydevd does not count as project code, and setting `IDE_PROJECT_ROOTS` to
 * that directory does not change the verdict. A checkbox that silently switches the feature off
 * would be worse than not having one.
 */
class ByExceptionBreakpointProperties : XBreakpointProperties<ByExceptionBreakpointProperties>() {

    var notifyOnRaise: Boolean = false
    var notifyOnTerminate: Boolean = true

    override fun getState(): ByExceptionBreakpointProperties = this

    override fun loadState(state: ByExceptionBreakpointProperties) {
        notifyOnRaise = state.notifyOnRaise
        notifyOnTerminate = state.notifyOnTerminate
    }

    /** The pydevd filter ids these flags select; empty means the breakpoint stops on nothing. */
    fun filters(): List<String> = buildList {
        if (notifyOnRaise) add(RAISED)
        if (notifyOnTerminate) add(UNCAUGHT)
    }

    companion object {
        const val RAISED: String = "raised"
        const val UNCAUGHT: String = "uncaught"
    }
}

/**
 * Break when a `.by` program raises.
 *
 * A type of our own for the same reason as [ByLineBreakpointType]: the Python plugin, whose
 * `PyExceptionBreakpointType` this would otherwise be, is not bundled in the IDE this targets.
 *
 * The platform also *requires* this type to exist whether or not anyone uses it — the DAP
 * breakpoint manager materialises its default breakpoint the first time a session stops on an
 * exception and throws if there is none.
 */
class ByExceptionBreakpointType : XBreakpointType<
    XBreakpoint<ByExceptionBreakpointProperties>,
    ByExceptionBreakpointProperties,
    >(ID, BasedPythonBundle.message("debug.breakpoint.exception.title")) {

    override fun createProperties(): ByExceptionBreakpointProperties = ByExceptionBreakpointProperties()

    override fun getDisplayText(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>): String {
        val properties = breakpoint.properties ?: return BasedPythonBundle.message("debug.breakpoint.exception.any")
        val parts = buildList {
            if (properties.notifyOnRaise) add(BasedPythonBundle.message("debug.breakpoint.exception.onRaise"))
            if (properties.notifyOnTerminate) add(BasedPythonBundle.message("debug.breakpoint.exception.onTerminate"))
        }
        if (parts.isEmpty()) return BasedPythonBundle.message("debug.breakpoint.exception.disabled")
        return BasedPythonBundle.message("debug.breakpoint.exception.any") + " — " + parts.joinToString(", ")
    }

    /**
     * The one breakpoint of this type. Exceptions have nothing to anchor a per-site breakpoint to,
     * so — as in every other debugger — there is a single always-present entry that is configured
     * rather than added.
     */
    override fun createDefaultBreakpoint(
        creator: XBreakpointCreator<ByExceptionBreakpointProperties>,
    ): XBreakpoint<ByExceptionBreakpointProperties> =
        creator.createBreakpoint(ByExceptionBreakpointProperties()).also { it.isEnabled = true }

    override fun isAddBreakpointButtonVisible(): Boolean = false

    override fun createCustomPropertiesPanel(
        project: Project,
    ): XBreakpointCustomPropertiesPanel<XBreakpoint<ByExceptionBreakpointProperties>> = ByExceptionBreakpointPanel()

    companion object {
        const val ID: String = "basedpython-exception"
    }
}

/** The "on raise" / "on termination" checkboxes in the Breakpoints dialog. */
private class ByExceptionBreakpointPanel :
    XBreakpointCustomPropertiesPanel<XBreakpoint<ByExceptionBreakpointProperties>>() {

    private val onRaise = JBCheckBox(BasedPythonBundle.message("debug.breakpoint.exception.onRaise"))
    private val onTerminate = JBCheckBox(BasedPythonBundle.message("debug.breakpoint.exception.onTerminate"))

    override fun getComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(onRaise)
        .addComponent(onTerminate)
        .panel

    override fun loadFrom(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        val properties = breakpoint.properties ?: ByExceptionBreakpointProperties()
        onRaise.isSelected = properties.notifyOnRaise
        onTerminate.isSelected = properties.notifyOnTerminate
    }

    override fun saveTo(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        val properties = breakpoint.properties ?: return
        properties.notifyOnRaise = onRaise.isSelected
        properties.notifyOnTerminate = onTerminate.isSelected
    }

    override fun dispose() {}
}

/**
 * Sends this session's exception breakpoints to the adapter.
 *
 * `DapXDebugProcess` registers a handler for *line* breakpoints only, so without this the type
 * above would be a checkbox that changed nothing — which is exactly what it was until now.
 */
internal class ByExceptionBreakpointHandler(
    private val session: DapDebugSession,
) : XBreakpointHandler<XBreakpoint<ByExceptionBreakpointProperties>>(ByExceptionBreakpointType::class.java) {

    override fun registerBreakpoint(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        session.commandProcessor.submitCommand {
            session.breakpointManager.run {
                breakpoint.toDapBreakpoints().forEach { addExceptionBreakpoint(it) }
            }
        }
    }

    override fun unregisterBreakpoint(
        breakpoint: XBreakpoint<ByExceptionBreakpointProperties>,
        temporary: Boolean,
    ) {
        session.commandProcessor.submitCommand {
            session.breakpointManager.run {
                breakpoint.toDapBreakpoints().forEach { removeExceptionBreakpoint(it) }
            }
        }
    }

    /**
     * One DAP breakpoint per selected filter — the protocol has no "raised *and* uncaught" filter,
     * they are separate ids, and `setExceptionBreakpoints` takes the list of them.
     */
    private fun XBreakpoint<ByExceptionBreakpointProperties>.toDapBreakpoints(): List<DapExceptionBreakpoint> {
        val properties = properties ?: return emptyList()
        val condition = conditionExpression?.expression
        return properties.filters().map { DapExceptionBreakpoint.create(it, condition, this) }
    }
}
