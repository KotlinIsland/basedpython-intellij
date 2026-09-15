package dev.basedpython.pycharm.debug.recompose

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import dev.basedpython.pycharm.debug.ByDapXDebugProcess
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend

/**
 * Turns every stop of a bpd session into a read of the compose runtime's trace.
 *
 * ## why a listener and not a pass
 *
 * The reason [dev.basedpython.pycharm.debug.dfa.ByDataFlowListener] gives: a pass runs when the
 * daemon decides, which is not when the program stopped. The stop is the event, and
 * [ByRecompositionSession] restarts the drawing once the answer is in.
 *
 * ## why bpd only, decided from the backend
 *
 * `bpd/recompositions` is bpd's own request and debugpy has nothing like it, and DAP has no
 * capability flag for a custom request — so, as hot reload does, this asks the thing that chose the
 * backend rather than guessing from the wire. A debugpy session is left alone entirely: no
 * listener, no request, nothing to refuse.
 *
 * ## the setting
 *
 * Read at every stop by the session rather than once here: a setting turned off while a program
 * is being debugged stops the requests at the next stop, and one turned on starts them, without
 * waiting for the next session. A separate listener from the data-flow one so the two settings
 * stay independent.
 */
class ByRecompositionListener : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        val dap = debugProcess as? ByDapXDebugProcess ?: return
        if (dap.backend != ByDebugBackend.BPD) return
        val session = debugProcess.session

        val service = ByRecompositionSession.getInstance(session.project)
        val link = ByRecompositionRequests(dap.dapDebugSession.commandProcessor)
        service.sessionStarted(link)
        // Owned by the service as well as the session, so a session outliving the plugin does not
        // keep a listener of ours; released when the session stops, so the service does not keep
        // every session it ever watched
        val watching = Disposer.newDisposable(service, "basedpython recompositions watcher")
        session.addSessionListener(StopWatcher(service, link, watching), watching)
    }

    /**
     * Pull at a stop, drop the labels on resume, forget on end.
     *
     * Not on `stackFrameChanged`: the trace is the program's, and selecting another frame asks the
     * same question with the same answer.
     */
    private class StopWatcher(
        private val service: ByRecompositionSession,
        private val link: ByRecompositionLink,
        private val lifetime: Disposable,
    ) : XDebugSessionListener {

        override fun sessionPaused() = service.paused(link)

        override fun sessionResumed() = service.resumed(link)

        override fun sessionStopped() {
            service.sessionEnded(link)
            Disposer.dispose(lifetime)
        }
    }
}
