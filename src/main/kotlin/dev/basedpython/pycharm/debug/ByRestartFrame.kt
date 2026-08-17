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
 * ## what it is, and what it is not
 *
 * The IDE's action is named after the JVM's, where resetting a frame **pops** it: the thread
 * returns to the caller, the call can be made again, and the parameters are the ones it was
 * originally given. Python has no such operation, and bpd — the only backend here that offers this
 * at all — says so in its own capability comment: it "re-enters the frame the thread is executing
 * with what its parameters hold **now**".
 *
 * So this moves the executing frame's instruction pointer back to its first line and runs the body
 * again over the locals as they stand. Confirmed live: stopped inside `work(n)` where `n` came in
 * as `1` and the body had made it `101`, a restart re-entered at the `def` line with `n` still
 * `101`. Nothing is unwound and nothing is restored.
 *
 * That is genuinely useful — it is the "I have seen enough, run this again" of a debugger — and it
 * is not what the action's name promises, which is why the difference is stated here and in
 * `docs/debugging.md` rather than left for somebody to discover from a wrong value.
 *
 * What a restart really did reaches the console through [ByMoved]: which locals cpython bound to
 * `None` on the way, and — the case worth knowing about here — that cpython **refused** the move.
 * A refusal is not an error response, so nothing in this class sees it: bpd answers the request
 * `success` and reports the refusal on `bpd/moved`. The catch below covers a request bpd would not
 * accept at all, which is a different thing.
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
        val dap = frame as? DefaultDapXStackFrame ?: return ThreeState.NO
        val declined = ByRestartFrame.decline(advertised(), isExecutingFrame(dap))
        return ThreeState.fromBoolean(declined == null)
    }

    override fun drop(frame: XStackFrame) {
        val dap = frame as? DefaultDapXStackFrame ?: return
        val declined = ByRestartFrame.decline(advertised(), isExecutingFrame(dap))
        if (declined != null) {
            // Reachable despite `canDropFrame`: the action is enabled against a stack that was
            // read a moment ago, and the thread can have moved on since.
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

    /**
     * Whether [frame] is the frame its thread is actually running.
     *
     * `DapThread.topFrame` is already held rather than fetched, so this is a comparison rather than
     * a request — which matters, because the platform asks this while deciding whether to enable a
     * toolbar button. It is null for a thread with no stack to be on top of, which is a thread that
     * is running rather than stopped: nothing to restart.
     */
    private fun isExecutingFrame(frame: DefaultDapXStackFrame): Boolean =
        frame.thread.topFrame?.id == frame.frame.id

    private companion object {
        private val LOG = Logger.getInstance(ByRestartFrameHandler::class.java)
    }
}

/** The decision behind [ByRestartFrameHandler], as pure logic. */
internal object ByRestartFrame {

    /**
     * Why this frame cannot be restarted, or null when it can.
     *
     * Two independent reasons, and neither is a guess:
     *
     *  - **the adapter has to say it can.** debugpy cannot — pydevd reports `supportsRestartFrame`
     *    as false — and asking anyway would send a request the adapter answers with an error that
     *    the platform then discards, so the action would look like it silently did nothing. Asked
     *    of the advertised capability rather than of [dev.basedpython.pycharm.debug.bpd.ByDebugBackend]
     *    because here the wire carries the answer: a backend that gains or loses the request says
     *    so in `initialize`, and believing it is strictly better than remembering it
     *  - **it has to be the frame the thread is executing.** bpd refuses any other, and the reason
     *    is CPython's rather than bpd's: a frame below the top is suspended inside a call, and
     *    assigning to its `f_lineno` is *accepted* while leaving it running on with a value stack
     *    that no longer matches. DAP's wording for the request implies discarding the frames above
     *    the named one, and there is no mechanism for that
     *
     * A null capability — `initialize` has not answered yet — declines. It becomes true a moment
     * later if the adapter does support it, and an action that is briefly grey is better than one
     * that is briefly wrong.
     */
    fun decline(advertised: Boolean?, isExecutingFrame: Boolean): String? = when {
        advertised != true -> "the debug adapter does not offer restartFrame"
        !isExecutingFrame -> "only the frame its thread is executing can be restarted"
        else -> null
    }
}
