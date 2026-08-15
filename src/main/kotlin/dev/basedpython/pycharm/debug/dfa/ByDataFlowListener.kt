package dev.basedpython.pycharm.debug.dfa

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.eclipse.lsp4j.TextDocumentIdentifier

private val LOG = Logger.getInstance(ByDataFlowListener::class.java)

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

    override fun processStarted(debugProcess: XDebugProcess) {
        val session = debugProcess.session
        if (!BasedPythonSettings.getInstance(session.project).debuggerDataFlow) return
        session.addSessionListener(StopWatcher(session))
    }

    private class StopWatcher(private val session: XDebugSession) : XDebugSessionListener {

        override fun sessionPaused() = onStop()

        /** Selecting another frame is another stop: a different question with a different answer. */
        override fun stackFrameChanged() = onStop()

        override fun sessionResumed() = forget()

        override fun sessionStopped() = forget()

        private fun forget() {
            ByDataFlowSession.getInstance(session.project).clear()
        }

        private fun onStop() {
            val project = session.project
            val position = session.currentPosition ?: return forget()
            val file = position.file
            if (file.fileType !is BasedPythonFileType) return forget()

            // Everything below reaches two other processes, so none of it may run on the EDT. The
            // stop itself is reported on it
            ApplicationManager.getApplication().executeOnPooledThread {
                val found = try {
                    analyse(project, file, position.line + 1)
                } catch (e: Exception) {
                    LOG.warn("data flow at a stop in ${file.name} failed", e)
                    emptyList()
                }
                ByDataFlowSession.getInstance(project).publish(file, found)
            }
        }

        /** Ask the debugger, then ask the server what the answer settles. */
        private fun analyse(project: Project, file: VirtualFile, line: Int): List<ByDataFlowFinding> {
            val document = ReadAction.compute<Document?, RuntimeException> {
                FileDocumentManager.getInstance().getDocument(file)
            } ?: return emptyList()

            val below = ReadAction.compute<Int, RuntimeException> {
                if (line - 1 in 0 until document.lineCount) document.getLineStartOffset(line - 1) else -1
            }
            if (below < 0) return emptyList()

            val text = ReadAction.compute<CharSequence, RuntimeException> { document.charsSequence }
            val names = ByDataFlowNames.below(text, below)
            if (names.isEmpty()) return emptyList()

            val facts = askDebugger(names) ?: return emptyList()
            val observations = ByDataFlowFacts.observationsOf(facts)
            if (observations.isEmpty()) return emptyList()

            return askServer(project, file, line, observations)
        }

        /**
         * `bpd/facts` for the frame the user is looking at.
         *
         * `null` when the adapter does not answer it, which is every debugpy session — that is the
         * ordinary case and not a failure worth reporting.
         */
        private fun askDebugger(names: List<String>): JsonObject? {
            val frame = session.currentStackFrame ?: return null
            return ByDataFlowRequests.facts(frame, names, FACTS_TIMEOUT_MS.toLong())
        }

        /** `by/dataFlowAt`, with what the debugger proved. */
        private fun askServer(
            project: Project,
            file: VirtualFile,
            line: Int,
            observations: List<ByObservation>,
        ): List<ByDataFlowFinding> {
            val server = LspServerManager.getInstance(project)
                .getServersForProvider(ByLspServerSupportProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(file) }
                ?: return emptyList()

            val params = ByDataFlowParams(
                textDocument = TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
                line = line,
                observations = observations,
            )
            return try {
                server.sendRequestSync(ANALYSIS_TIMEOUT_MS) {
                    (it as ByDataFlowServer).dataFlowAt(params)
                }.orEmpty()
            } catch (e: Exception) {
                LOG.warn("by/dataFlowAt failed", e)
                emptyList()
            }
        }
    }

}
