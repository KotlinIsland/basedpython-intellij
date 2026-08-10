package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
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

// Exception breakpoints live in ByExceptionBreakpoint.kt — they carry properties and a panel.
