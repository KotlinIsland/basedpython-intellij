package dev.basedpython.pycharm.debug

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.dialect.BasedPythonFileTypeOverrider
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Which files this plugin's breakpoints — line, and therefore log points too — belong in.
 *
 * `.by` always. `.py` **only when this plugin owns the file type**, which is the question
 * [BasedPythonFileTypeOverrider] already answers from the project, the *Settings | basedpython*
 * ownership choice, and whether another plugin provides the Python language. Asking the registry
 * rather than re-deriving it keeps the two answers from drifting, and it is what avoids a second
 * breakpoint type in PyCharm: the platform collects *every* type whose `canPutAt` says yes and puts
 * a "choose a type" popup in front of the user when more than one does. Where PyCharm's
 * `PyLineBreakpointType` claims a `.py` file we do not, and where we claim it that type does not —
 * its own `canPutAt` asks whether the file is of `PythonFileType`, which is exactly what the
 * overrider changes.
 *
 * A `.py` breakpoint needs no source map. `by run` never transpiles a plain `.py`, so the
 * interpreter loads the file the user is looking at and both backends place the breakpoint on it
 * directly — pydevd because the file is simply not one it was given a map for, bpd because its
 * mapping layer passes everything that is not `.by` straight through. Verified live against
 * debugpy 1.8.21: the breakpoint reports `verified`, stops in `helper.py` with locals intact, and
 * the `.by` frames below it are still mapped onto their sources.
 *
 * `.byi` and `.pyi` stubs are excluded: they declare, they do not execute.
 */
object ByBreakpointFiles {

    fun accepts(file: VirtualFile?): Boolean = when (file?.extension?.lowercase()) {
        BasedPythonFileType.INSTANCE.defaultExtension -> true
        BasedPythonFileTypeOverrider.OVERRIDABLE_EXTENSION ->
            FileTypeRegistry.getInstance().isFileOfType(file, BasedPythonFileType.INSTANCE)
        else -> false
    }
}

/**
 * Line breakpoints in basedpython files.
 *
 * A type of our own rather than the Python plugin's `PyLineBreakpointType`, which is not available:
 * the IDE this plugin targets does not bundle the Python plugin (FEATURES.md §5), and a session
 * would not see that type's breakpoints anyway — the platform dispatches a breakpoint to a handler
 * by exact type class, never by assignability. It carries no properties — everything the debugger
 * needs is the file and the line, and the mapping onto the transpiled output happens in the
 * debuggee (see [ByDebugProtocolServer]).
 *
 * Which files it accepts is [ByBreakpointFiles].
 */
class ByLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(
    ID,
    BasedPythonBundle.message("debug.breakpoint.line.title"),
) {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        ByBreakpointFiles.accepts(file)

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
