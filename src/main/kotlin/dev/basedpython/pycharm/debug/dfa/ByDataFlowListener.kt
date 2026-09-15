package dev.basedpython.pycharm.debug.dfa

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.frame.XStackFrame
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.eclipse.lsp4j.TextDocumentIdentifier

/** How long the debuggee and the server each get before a stop is given up on. */
private const val FACTS_TIMEOUT_MS = 2_000
private const val ANALYSIS_TIMEOUT_MS = 2_000

/**
 * Turns every stop into a question, and the answer into something drawn.
 *
 * ## Why a listener and not a pass
 *
 * An inlay or highlighting pass runs when the daemon decides to run it, which is not when the
 * program stops. This question only has an answer while a thread is held, and the answer changes
 * on every step — so the stop is the event, and [ByDataFlowSession] restarts the drawing.
 *
 * ## The two round trips
 *
 * The debugger is asked what it can prove about the names the code below the stop line mentions,
 * and the language server is asked what those facts settle. Neither knows about the other: `bpd`
 * has never heard of a type and `by` has never heard of a debug session. This is the only place
 * that knows both, which is why the translation lives in [ByDataFlowFacts] beside it.
 *
 * ## Why bpd only
 *
 * The facts carry **how long each reading stays true**, and that judgement can only be made by
 * something holding the object — whether its type is a heap type, whether a length can change. A
 * DAP `variables` reply carries none of it. So a session against debugpy asks nothing and draws
 * nothing, rather than drawing something built on a guess.
 */
class ByDataFlowListener : XDebuggerManagerListener {

    /**
     * Watches every session, whatever the setting says now: it is read at each stop, so turning it
     * on mid-session starts the questions at the next stop and turning it off stops them.
     *
     * The watcher lives no longer than the data-flow service, so a session still running when the
     * plugin is unloaded does not keep a listener of ours, and no longer than the session, so the
     * service does not keep every session it ever watched.
     */
    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        val watching = Disposer.newDisposable(ByDataFlowSession.getInstance(session.project), "basedpython data flow watcher")
        session.addSessionListener(StopWatcher(session, watching), watching)
    }

    private class StopWatcher(
        private val session: XDebugSession,
        private val lifetime: Disposable,
    ) : XDebugSessionListener {

        override fun sessionPaused() = onStop()

        /** Selecting another frame is another stop: a different question with a different answer. */
        override fun stackFrameChanged() = onStop()

        override fun sessionResumed() = forget()

        override fun sessionStopped() {
            forget()
            Disposer.dispose(lifetime)
        }

        private fun forget() {
            ByDataFlowSession.getInstance(session.project).clear()
        }

        /**
         * Reads where the program is *here*, on the thread the stop is reported on. Asked later from
         * another thread, the session could already be at the next stop, and the question would be
         * about one stop and its answer drawn at another.
         */
        private fun onStop() {
            val project = session.project
            val data = ByDataFlowSession.getInstance(project)
            if (!BasedPythonSettings.getInstance(project).debuggerDataFlow) return data.clear()
            val position = session.currentPosition ?: return data.clear()
            val file = position.file
            if (file.fileType !is BasedPythonFileType) return data.clear()
            val frame = session.currentStackFrame ?: return data.clear()
            val line = position.line + 1

            // Everything the analysis does reaches two other processes, so none of it runs here: a
            // frame selection is reported on the EDT
            data.stopped(file) { analyse(project, file, line, frame) }
        }

        /** Ask the debugger, then ask the server what the answer settles. */
        private suspend fun analyse(
            project: Project,
            file: VirtualFile,
            line: Int,
            frame: XStackFrame,
        ): List<ByDataFlowFinding> {
            val names = readAction {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@readAction null
                if (line - 1 !in 0 until document.lineCount) return@readAction null
                ByDataFlowNames.below(document.immutableCharSequence, document.getLineStartOffset(line - 1))
            }
            if (names.isNullOrEmpty()) return emptyList()

            // `null` when the adapter does not answer it, which is every debugpy session — that is
            // the ordinary case and not a failure worth reporting
            val facts = ByDataFlowRequests.facts(frame, names, FACTS_TIMEOUT_MS.toLong()) ?: return emptyList()
            val observations = ByDataFlowFacts.observationsOf(facts)
            if (observations.isEmpty()) return emptyList()

            return askServer(project, file, line, observations)
        }

        /** `by/dataFlowAt`, with what the debugger proved. */
        private suspend fun askServer(
            project: Project,
            file: VirtualFile,
            line: Int,
            observations: List<ByObservation>,
        ): List<ByDataFlowFinding> {
            val server = byServerFor(project, file) ?: return emptyList()

            val params = ByDataFlowParams(
                textDocument = TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
                line = line,
                observations = observations,
            )
            return server.awaitBy("by/dataFlowAt", ANALYSIS_TIMEOUT_MS.toLong()) {
                (it as ByDataFlowServer).dataFlowAt(params)
            }.value.orEmpty()
        }
    }
}
