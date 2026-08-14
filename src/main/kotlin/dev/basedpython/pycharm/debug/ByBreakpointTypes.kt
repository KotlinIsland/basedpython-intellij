package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
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

    /**
     * Lets a breakpoint of this type sit *between* two lines rather than on one, which is what a log
     * point is.
     *
     * Defaults to false, and while it is false the gutter gap does not exist for `.by` files no
     * matter what else is in place. `XDebuggerLineChangeHandler` asks each line breakpoint type this
     * question before it will treat a hover as an inter-line one; with no type saying yes,
     * `BreakpointPromoterEditorListener` sets none of the gutter's hover properties, so there is no
     * icon, no tooltip, and not even a cursor change — while an
     * `InterLineBreakpointConfigurationProvider` goes on offering a perfectly good configuration
     * that nothing ever asks for. Kotlin's and Java's types override it; this one did not, and that
     * was the whole of why the gap never appeared.
     */
    override fun supportsInterLinePlacement(): Boolean = true

    /**
     * Makes every expression field the IDE offers for one of these breakpoints a basedpython editor
     * rather than a plain text box — and is what lets the inter-line log point editor open at all.
     * See [ByDebuggerEditorsProvider].
     */
    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<XBreakpointProperties<*>>,
        project: Project,
    ): XDebuggerEditorsProvider = ByDebuggerEditorsProvider

    companion object {
        const val ID: String = "basedpython-line"
    }
}

// Exception breakpoints live in ByExceptionBreakpoint.kt — they carry properties and a panel.
