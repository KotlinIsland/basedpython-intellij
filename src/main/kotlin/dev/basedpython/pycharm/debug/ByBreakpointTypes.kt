package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Line breakpoints in `.by` files.
 *
 * A type of our own rather than the Python plugin's `PyLineBreakpointType`, which is not available:
 * the IDE this plugin targets does not bundle the Python plugin (FEATURES.md §5). It carries no
 * properties — everything the debugger needs is the file and the line, and the mapping onto the
 * transpiled output happens in the debuggee (see [ByDebugProtocolServer]).
 *
 * `.byi` stubs are excluded: they declare, they do not execute.
 */
class ByLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    ID,
    BasedPythonBundle.message("debug.breakpoint.line.title"),
) {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.extension.equals("by", ignoreCase = true)

    override fun getDisplayText(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>): String =
        "${breakpoint.shortFilePath}:${breakpoint.line + 1}"

    companion object {
        const val ID: String = "basedpython-line"
    }
}

/**
 * The exception breakpoint type `DapBreakpointsDescription` requires.
 *
 * The platform's breakpoint manager materialises this type's default breakpoint the first time a
 * session stops on an exception, and throws if the type has none — so a type must exist even though
 * this plugin does not offer configurable exception breakpoints. `DapXDebugProcess` registers a
 * handler for line breakpoints only, so nothing here is ever sent to the adapter; it exists to give
 * an exception stop something to attach itself to.
 *
 * Hidden from the Breakpoints dialog for exactly that reason: a checkbox that changed nothing would
 * be worse than no checkbox.
 */
class ByExceptionBreakpointType : XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>(
    ID,
    BasedPythonBundle.message("debug.breakpoint.exception.title"),
) {
    override fun getDisplayText(breakpoint: XBreakpoint<XBreakpointProperties<*>>): String =
        BasedPythonBundle.message("debug.breakpoint.exception.title")

    override fun createDefaultBreakpoint(
        creator: XBreakpointCreator<XBreakpointProperties<*>>,
    ): XBreakpoint<XBreakpointProperties<*>> = creator.createBreakpoint(null)

    override fun isAddBreakpointButtonVisible(): Boolean = false

    override fun shouldShowInBreakpointsDialog(project: Project): Boolean = false

    companion object {
        const val ID: String = "basedpython-exception"
    }
}
