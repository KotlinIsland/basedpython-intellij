package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.FormBuilder
import com.intellij.xdebugger.XDebuggerManager
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
 * The breakpoint is the type's default, *Any exception*, when [exceptionClass] is null, and a rule
 * for one class and its subclasses — PyCharm's python exception breakpoint — when it names one. A
 * class breakpoint is bpd's alone ([ByBreakpointChannel]); the default's two flags are filter ids
 * every backend has.
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
 * The default has no "ignore library code" option. pydevd spells it as a `:ignoreLibraries` suffix,
 * and with it the breakpoint never fires at all here — the transpiled program lives in a temp
 * directory that pydevd does not count as project code, and setting `IDE_PROJECT_ROOTS` to that
 * directory does not change the verdict — and under bpd the filters follow the session's
 * `justMyCode`. A class breakpoint has [ignoreLibraries], PyCharm's *Ignore library files*, which is
 * bpd's rule's own `libraries`.
 */
class ByExceptionBreakpointProperties : XBreakpointProperties<ByExceptionBreakpointProperties>() {

    var notifyOnRaise: Boolean = false
    var notifyOnTerminate: Boolean = true

    /** The class, as the program names it (`KeyError`, `json.JSONDecodeError`); null for *Any exception*. */
    var exceptionClass: String? = null

    /** A library's raise of the class does not stop. A class breakpoint's alone. */
    var ignoreLibraries: Boolean = false

    override fun getState(): ByExceptionBreakpointProperties = this

    override fun loadState(state: ByExceptionBreakpointProperties) {
        notifyOnRaise = state.notifyOnRaise
        notifyOnTerminate = state.notifyOnTerminate
        exceptionClass = state.exceptionClass
        ignoreLibraries = state.ignoreLibraries
    }

    /** bpd's rule for a class breakpoint; null for the default, and for one that stops on nothing. */
    internal fun rule(): ByBreakpointChannel.ClassRule? {
        val name = exceptionClass?.takeIf { it.isNotBlank() } ?: return null
        // neither ticked is a breakpoint that stops on nothing, not bpd's `never`, which would hold
        // the class back from the default's filters as well
        if (!notifyOnRaise && !notifyOnTerminate) return null
        return ByBreakpointChannel.ClassRule(name, raised = notifyOnRaise, uncaught = notifyOnTerminate, libraries = !ignoreLibraries)
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
 * Break when a `.by` program raises: *Any exception*, the type's one default breakpoint, and one per
 * class added with the Breakpoints dialog's *+*, as PyCharm's python exception breakpoints are.
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
        val what = properties.exceptionClass ?: BasedPythonBundle.message("debug.breakpoint.exception.any")
        val parts = buildList {
            if (properties.notifyOnRaise) add(BasedPythonBundle.message("debug.breakpoint.exception.onRaise"))
            if (properties.notifyOnTerminate) add(BasedPythonBundle.message("debug.breakpoint.exception.onTerminate"))
        }
        if (parts.isEmpty()) return what + " — " + BasedPythonBundle.message("debug.breakpoint.exception.disabled")
        return what + " — " + parts.joinToString(", ")
    }

    /**
     * *Any exception*. Exceptions have nothing to anchor a per-site breakpoint to, so — as in every
     * other debugger — there is a single always-present entry that is configured rather than added;
     * a class of its own is added beside it.
     */
    override fun createDefaultBreakpoint(
        creator: XBreakpointCreator<ByExceptionBreakpointProperties>,
    ): XBreakpoint<ByExceptionBreakpointProperties> =
        creator.createBreakpoint(ByExceptionBreakpointProperties()).also { it.isEnabled = true }

    override fun isAddBreakpointButtonVisible(): Boolean = true

    /** A class breakpoint, for the class the dialog names; stops where one leaves the program, as PyCharm's does. */
    override fun addBreakpoint(project: Project, parentComponent: JComponent?): XBreakpoint<ByExceptionBreakpointProperties>? {
        val name = ByExceptionClassDialog(project).showAndGetName() ?: return null
        val properties = ByExceptionBreakpointProperties().apply { exceptionClass = name }
        return XDebuggerManager.getInstance(project).breakpointManager.addBreakpoint(this, properties)
    }

    override fun createCustomPropertiesPanel(
        project: Project,
    ): XBreakpointCustomPropertiesPanel<XBreakpoint<ByExceptionBreakpointProperties>> = ByExceptionBreakpointPanel()

    companion object {
        const val ID: String = "basedpython-exception"
    }
}

/** The "on raise" / "on termination" checkboxes in the Breakpoints dialog, and a class's "ignore library files". */
private class ByExceptionBreakpointPanel :
    XBreakpointCustomPropertiesPanel<XBreakpoint<ByExceptionBreakpointProperties>>() {

    private val onRaise = JBCheckBox(BasedPythonBundle.message("debug.breakpoint.exception.onRaise"))
    private val onTerminate = JBCheckBox(BasedPythonBundle.message("debug.breakpoint.exception.onTerminate"))
    private val ignoreLibraries = JBCheckBox(BasedPythonBundle.message("debug.breakpoint.exception.ignoreLibraries"))

    override fun getComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(onRaise)
        .addComponent(onTerminate)
        .addComponent(ignoreLibraries)
        .panel

    override fun loadFrom(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        val properties = breakpoint.properties ?: ByExceptionBreakpointProperties()
        onRaise.isSelected = properties.notifyOnRaise
        onTerminate.isSelected = properties.notifyOnTerminate
        ignoreLibraries.isSelected = properties.ignoreLibraries
        // the default's filters follow the session's just my code; only a class's rule says its own
        ignoreLibraries.isVisible = properties.exceptionClass != null
    }

    override fun saveTo(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        val properties = breakpoint.properties ?: return
        properties.notifyOnRaise = onRaise.isSelected
        properties.notifyOnTerminate = onTerminate.isSelected
        properties.ignoreLibraries = ignoreLibraries.isSelected
    }

    override fun dispose() {}
}

/**
 * Sends this session's exception breakpoints to the adapter.
 *
 * `DapXDebugProcess` registers handlers for *line* and function breakpoints only, so without this
 * the type above would be a checkbox that changed nothing — which is exactly what it once was.
 * [ByDapXDebugProcess] adds it to the platform's handlers.
 *
 * *Any exception* goes to the platform's breakpoint manager as filters, which it sends in
 * `setExceptionBreakpoints`; a class breakpoint goes to [ByBreakpointChannel], since that request
 * as the platform sends it can carry no class.
 *
 * What was registered is remembered per breakpoint, and exactly that is what an unregistration
 * removes. Rebuilding it from the breakpoint instead removes nothing: the platform unregisters an
 * edited breakpoint *after* the Breakpoints dialog has written the new flags into its properties,
 * and the manager's set is keyed on filter and condition — so unticking "on raise" asked it to
 * remove only `uncaught`, and `raised` went on stopping the program.
 */
internal class ByExceptionBreakpointHandler(
    private val session: DapDebugSession,
    /** Where a class breakpoint goes: bpd's own request, since the platform's never carries one. */
    private val classes: ByBreakpointChannel,
) : XBreakpointHandler<XBreakpoint<ByExceptionBreakpointProperties>>(ByExceptionBreakpointType::class.java) {

    private val registered = HashMap<XBreakpoint<ByExceptionBreakpointProperties>, List<DapExceptionBreakpoint>>()

    override fun registerBreakpoint(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) {
        if (breakpoint.properties?.exceptionClass != null) return classes.setClass(breakpoint, breakpoint.properties?.rule())
        val added = breakpoint.toDapBreakpoints()
        val replaced = synchronized(registered) { registered.put(breakpoint, added) }.orEmpty()
        session.post {
            with(session.breakpointManager) {
                replaced.forEach { removeExceptionBreakpoint(it) }
                added.forEach { addExceptionBreakpoint(it) }
            }
        }
    }

    override fun unregisterBreakpoint(
        breakpoint: XBreakpoint<ByExceptionBreakpointProperties>,
        temporary: Boolean,
    ) {
        if (breakpoint.properties?.exceptionClass != null) return classes.setClass(breakpoint, null)
        val removed = synchronized(registered) { registered.remove(breakpoint) } ?: return
        session.post {
            with(session.breakpointManager) {
                removed.forEach { removeExceptionBreakpoint(it) }
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
