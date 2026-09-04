package dev.basedpython.pycharm.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Line breakpoints in basedpython files — `.by`, and the `.py` files this plugin owns
 * ([BasedPythonSources]).
 *
 * A type of our own rather than the Python plugin's `PyLineBreakpointType`, which is not available:
 * the IDE this plugin targets does not bundle the Python plugin (FEATURES.md §5), and a session
 * would not see that type's breakpoints anyway — the platform dispatches a breakpoint to a handler
 * by exact type class, never by assignability. Everything the debugger needs is the file and the
 * line — the mapping onto the transpiled output happens in the debuggee (see
 * [ByDebugProtocolServer]) — so its [ByBreakpointProperties] carry one thing the debugger does not
 * use at all: whether this breakpoint is a log point.
 *
 * Claiming a `.py` we own — and only one we own — is also what keeps this from being a second
 * breakpoint type in PyCharm: the platform collects *every* type whose [canPutAt] says yes and puts
 * a "choose a type" popup in front of the user when more than one does, and
 * `PyLineBreakpointType.canPutAt` asks whether the file is of `PythonFileType`, which is exactly
 * what the file-type overrider changes.
 *
 * A `.py` breakpoint needs no source map. `by run` never transpiles a plain `.py`, so the
 * interpreter loads the file the user is looking at, and both backends place the breakpoint on it
 * directly — pydevd because the file is simply not one it was given a map for, bpd because its
 * mapping layer passes everything that is not `.by` straight through. Verified live against
 * debugpy 1.8.21: the breakpoint reports `verified`, stops in `helper.py` with locals intact, and
 * the `.by` frames below it are still mapped onto their sources.
 */
class ByLineBreakpointType : XLineBreakpointType<ByBreakpointProperties>(
    ID,
    BasedPythonBundle.message("debug.breakpoint.line.title"),
) {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): ByBreakpointProperties =
        ByBreakpointProperties()

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        BasedPythonSources.isOwnedSource(file)

    override fun getDisplayText(breakpoint: XLineBreakpoint<ByBreakpointProperties>): String =
        "${breakpoint.shortFilePath}:${breakpoint.line + 1}"

    /**
     * Lets a breakpoint of this type sit *between* two lines rather than on one, which is what a log
     * point is.
     *
     * `@ApiStatus.Internal`, and the single line that makes log points come out right. Defaults to
     * false, and while it is false the gutter gap does not exist for `.by` files no matter what else
     * is in place: `XDebuggerLineChangeHandler` asks each line breakpoint type this question before
     * it will treat a hover as an inter-line one, so with no type saying yes
     * `BreakpointPromoterEditorListener` sets none of the gutter's hover properties — no icon, no
     * tooltip, not even a cursor change — while an `InterLineBreakpointConfigurationProvider` goes
     * on offering a perfectly good configuration that nothing ever asks for.
     *
     * It is also what puts the yellow dot in the gap rather than a line below it, in both IDEs, and
     * what lets IntelliJ IDEA's own *Add Logpoint* make a `.by` log point at all —
     * `XBreakpointUIUtil.supportsPlacement` filters types that say no out of any `INTER_LINE`
     * toggle. See docs/internal-api.md.
     */
    override fun supportsInterLinePlacement(): Boolean = true

    /**
     * Makes every expression field the IDE offers for one of these breakpoints a basedpython editor
     * rather than a plain text box — and is what lets the inter-line log point editor open at all.
     * See [ByDebuggerEditorsProvider].
     */
    override fun getEditorsProvider(
        breakpoint: XLineBreakpoint<ByBreakpointProperties>,
        project: Project,
    ): XDebuggerEditorsProvider = ByDebuggerEditorsProvider

    companion object {
        const val ID: String = "basedpython-line"
    }
}

// Exception breakpoints live in ByExceptionBreakpoint.kt — they carry properties and a panel.

/**
 * What a basedpython line breakpoint remembers about itself beyond its file and line.
 *
 * One flag, and it exists because the platform's own answer to the question is internal. A log
 * point used to be told apart from an ordinary breakpoint by its vertical placement —
 * `XLineBreakpoint.placement == XLineBreakpointVerticalPlacement.INTER_LINE`, set when the platform
 * created it in the gutter gap — and `getPlacement`, the placement enum and the gap machinery that
 * set it are all `@ApiStatus.Internal`, which JetBrains Marketplace declined this plugin for. See
 * docs/internal-api.md.
 *
 * So the fact is recorded here instead, where it is this plugin's own and is persisted with the
 * breakpoint by the platform's ordinary state serialisation. It is also the more honest place for
 * it: whether a breakpoint logs instead of stopping is a property of the breakpoint, not of where
 * it happens to be drawn.
 */
class ByBreakpointProperties : XBreakpointProperties<ByBreakpointProperties.State>() {

    /** Serialised form. A public no-arg constructor and mutable fields are what the platform needs. */
    class State {
        @JvmField var isLogpoint: Boolean = false
    }

    private var state = State()

    /** True when this breakpoint logs an expression instead of suspending — a log point. */
    var isLogpoint: Boolean
        get() = state.isLogpoint
        set(value) { state.isLogpoint = value }

    override fun getState(): State = state

    override fun loadState(from: State) {
        state = from
    }
}
