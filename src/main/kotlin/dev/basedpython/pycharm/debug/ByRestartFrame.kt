package dev.basedpython.pycharm.debug

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.ThreeState
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.frame.XDropFrameHandler
import com.intellij.xdebugger.frame.XStackFrame
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.debug.RestartFrameArguments

/**
 * *Reset Frame*, for adapters that can do it.
 *
 * ## what it does
 *
 * The IDE's action is named after the JVM's, where resetting a frame **pops** it: the thread returns
 * to the caller, the call can be made again, and the parameters are the ones it was originally
 * given. CPython has no such operation, and bpd builds the nearest honest thing out of jumps — two
 * mechanisms, and it picks between them itself:
 *
 *  - **reset in place.** The frame's instruction pointer goes back to its first line and the locals
 *    a fresh call would not have bound are put back to *unbound* rather than to `None`. The frame
 *    object is the one the program already had and the caller is never touched, so nothing else on
 *    the caller's line runs a second time
 *  - **rewind through the caller.** The frame is forced to return and the caller's line is run
 *    again, so the interpreter builds a frame that has never run. This is the one that serves a
 *    frame which has written over one of its own parameters: the parameter slots are the only place
 *    what the call passed still exists, and re-evaluating the call site is the only way back to it
 *
 * A frame below the top is reached by forcing the frames above it out, innermost first, each made to
 * return the way the rewind forces its own frame out. That is why this offers every frame rather
 * than only the executing one — see [ByRestartFrame.decline].
 *
 * Measured against bpd, stopped in `work(n)` where `n` arrived as `1` and the body had made it
 * `101`: the reset is refused because the frame rebinds a parameter, bpd falls back to the rewind,
 * and the call is made again with `n` back to `1`. Side effects the old frame already performed are
 * **not** undone, and no block cleanup runs — a `with` the frame was inside gets no `__exit__`.
 *
 * ## what the user is told
 *
 * Everything a restart really did comes back on the console, from bpd, in one place: which locals
 * were emptied, which frames were discarded and whether any of them held a block open. This class
 * adds nothing to that and must not — two spellings of the same facts drift.
 *
 * What it does own is a **refused** request. bpd answers one with an error response, and the
 * platform drops a failed request's message on the floor (see `scratch.ij-dap-issues.md`), so
 * without the catch below a refusal a person asked for would look like a button that did nothing.
 *
 * ## why this exists at all
 *
 * `restartFrame` is a DAP request and `supportsRestartFrame` a DAP capability, and bpd implements
 * both. The platform's DAP client does not: `intellij.platform.dap` contains no reference to
 * `restartFrame` or to [XDropFrameHandler], so the action stays greyed out however much the adapter
 * advertises. This is the missing bridge, and it is an ordinary supported override rather than a
 * workaround — `XDebugProcess.getDropFrameHandler` exists for exactly this.
 */
internal class ByRestartFrameHandler(
    private val project: Project,
    /** The adapter's latest advertised `supportsRestartFrame`, or null before `initialize` answered. */
    private val advertised: () -> Boolean?,
) : XDropFrameHandler {

    override fun canDropFrame(frame: XStackFrame): ThreeState {
        if (frame !is DefaultDapXStackFrame) return ThreeState.NO
        return ThreeState.fromBoolean(ByRestartFrame.decline(advertised()) == null)
    }

    override fun drop(frame: XStackFrame) {
        val dap = frame as? DefaultDapXStackFrame ?: return
        val declined = ByRestartFrame.decline(advertised())
        if (declined != null) {
            // Reachable despite `canDropFrame`: the action is enabled against a stack that was
            // read a moment ago, and the adapter can have answered `initialize` since.
            LOG.info("restart frame declined: $declined")
            return
        }
        dap.commandProcessor.submitCommand {
            try {
                server.restartFrame(RestartFrameArguments().apply { frameId = dap.frame.id }).await()
            } catch (e: Exception) {
                // Ours to report. The platform drops a failed request's message on the floor
                // (see scratch.ij-dap-issues.md), and this one is a request the *user* asked for,
                // so silence would read as the action doing nothing.
                LOG.info("restartFrame failed", e)
                ByCli.notifyWarning(
                    project,
                    BasedPythonBundle.message("debug.restartFrame.refused.title"),
                    e.message ?: BasedPythonBundle.message("debug.restartFrame.refused.generic"),
                )
            }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(ByRestartFrameHandler::class.java)
    }
}

/** The decision behind [ByRestartFrameHandler], as pure logic. */
internal object ByRestartFrame {

    /**
     * Why no frame can be restarted, or null when they can.
     *
     * One reason, and it is not a guess: **the adapter has to say it can.** debugpy cannot — pydevd
     * reports `supportsRestartFrame` as false — and asking anyway would send a request the adapter
     * answers with an error that the platform then discards, so the action would look like it
     * silently did nothing. Asked of the advertised capability rather than of
     * [dev.basedpython.pycharm.debug.bpd.ByDebugBackend] because here the wire carries the answer: a
     * backend that gains or loses the request says so in `initialize`, and believing it is strictly
     * better than remembering it.
     *
     * A null capability — `initialize` has not answered yet — declines. It becomes true a moment
     * later if the adapter does support it, and an action that is briefly grey is better than one
     * that is briefly wrong.
     *
     * **Nothing here is about which frame it is.** It used to be: bpd refused any frame but the one
     * its thread was executing, because a frame below the top is suspended inside a call and CPython
     * *crashes* rather than refuses when one of those is moved. bpd now reaches such a frame by
     * forcing the frames above it out, innermost first, so the limit is gone — and it was the
     * plugin's copy of that limit, not bpd's, that kept the action grey on a caller after bpd
     * lifted it. What is left is bpd's to decide per frame and per shape of call site, decided off
     * the bytecode before anything is attempted and reported as a refused request. This cannot
     * mirror that list without being a second, slower, wrong copy of it.
     */
    fun decline(advertised: Boolean?): String? =
        "the debug adapter does not offer restartFrame".takeIf { advertised != true }
}
