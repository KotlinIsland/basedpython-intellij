package dev.basedpython.pycharm.debug

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.xdebugger.DapXSuspendContext
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.jetbrains.dap.protocol.DapLine
import com.jetbrains.dap.protocol.DapServer
import com.jetbrains.dap.protocol.GotoArguments
import com.jetbrains.dap.protocol.GotoTargetsArguments
import com.jetbrains.dap.protocol.Source
import com.jetbrains.dap.protocol.ThreadId
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Set next statement for one `.by` debug session: DAP's `gotoTargets` for a line, then `goto`.
 *
 * The platform's DAP client drops frames and steps into calls, but has no set next statement, and
 * the XDebugger API has no hook for one either: PyCharm's *Jump To Cursor* is an action of the Python
 * plugin's own, for its own debug processes. This is the same thing for the processes
 * [ByDapXDebugProcess] runs.
 *
 * ## what each adapter does with it
 *
 * Offered where the adapter answered `initialize` with `supportsGotoTargetsRequest` and the backend
 * can in fact move a frame on a `.by` line, which is bpd and not debugpy — see
 * [ByDebugBackend.setsNextStatement] for what debugpy does with one. bpd offers a target only on the
 * file a held thread is executing, one per such thread, and leaves whether the frame can get there
 * to cpython when the move is made: a refused move is answered `success`, with the reason on
 * `bpd/moved` ([ByMoved]) and a fresh `stopped` event where the frame still is.
 *
 * Moved or not, where the frame now is arrives as that `stopped` event, which the platform takes as
 * it takes any other, as it does after a dropped frame.
 */
internal class ByJumps(
    private val session: XDebugSession,
    private val dap: DapDebugSession,
    scope: CoroutineScope,
    /** The backend behind the session, or null for one that never started. */
    private val backend: ByDebugBackend?,
) {

    /**
     * Whether Jump To Cursor is offered: the backend can move a frame on a `.by` line
     * ([ByDebugBackend.setsNextStatement]) and the adapter said it takes `gotoTargets` and `goto`.
     */
    val supported: Boolean
        get() = backend?.setsNextStatement == true && adapterTakesGoto

    @Volatile
    private var adapterTakesGoto: Boolean = false

    /** The requests themselves, and what bpd says about each move. */
    private val requests = ByJumpRequests()

    init {
        scope.launch {
            dap.capabilities.collect { adapterTakesGoto = it.supportsGotoTargetsRequest == true }
        }
    }

    /** bpd's account of a move, which says whether cpython made it. */
    fun moved(moved: ByMoved) = requests.moved(moved)

    /**
     * Moves the paused thread's frame to [line] (0-based) of [file], or says why not through
     * [refused], on whichever thread the answer came in on.
     *
     * Sent on the pause's own executor, so a jump asked for just as the thread resumes is dropped
     * with the pause instead of landing on a running thread.
     */
    fun jump(file: VirtualFile, line: Int, refused: (String) -> Unit) {
        val context = session.suspendContext as? DapXSuspendContext
        val thread = context?.activeThread
        if (context == null || thread == null) {
            refused(BasedPythonBundle.message("debug.jump.notPaused"))
            return
        }
        val source = Source(name = file.name, path = dap.virtualFileResolver.getAdapterPath(file))
        context.executor.post { requests.move(server, thread.id, source, line + 1, refused) }
    }
}

/**
 * `gotoTargets` then `goto`, and what the adapter said about the move — apart from any session, so
 * it can be driven against the platform's DAP client with a stand-in adapter.
 */
internal class ByJumpRequests {

    /** What to do with the `bpd/moved` of the jump in flight, taken by the first one to arrive. */
    private val awaiting = AtomicReference<((ByMoved) -> Unit)?>(null)

    /** bpd's account of a move, which says whether cpython made it. */
    fun moved(moved: ByMoved) {
        awaiting.getAndSet(null)?.invoke(moved)
    }

    /**
     * Asks [server] to move [thread]'s frame to [line] (1-based, as the platform's client counts)
     * of [source], and calls [refused] with the reason when it will not: the adapter's own sentence
     * for a request it refused, cpython's for a move bpd tried and cpython would not make.
     */
    suspend fun move(server: DapServer, thread: ThreadId, source: Source, line: Int, refused: (String) -> Unit) {
        val targets = try {
            server.gotoTargets(GotoTargetsArguments(source = source, line = DapLine(line))).targets
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("gotoTargets failed", e)
            refused(ByDapRequests.refusalOf(e) ?: BasedPythonBundle.message("debug.jump.failed"))
            return
        }
        if (targets.isEmpty()) {
            refused(BasedPythonBundle.message("debug.jump.noTarget", line, source.name ?: source.path.orEmpty()))
            return
        }
        // bpd mints one target per held thread executing the file, and refuses one used on a
        // thread it was not minted for, so the first it takes for this thread is the one
        var refusal: String? = null
        for (target in targets) {
            awaiting.set { moved ->
                if (moved.refused) {
                    refused(
                        BasedPythonBundle.message(
                            "debug.jump.refused",
                            moved.wanted ?: line,
                            moved.refusal ?: BasedPythonBundle.message("debug.jump.failed"),
                        ),
                    )
                }
            }
            try {
                server.goto(GotoArguments(threadId = thread, targetId = target.id))
                return
            } catch (e: CancellationException) {
                awaiting.set(null)
                throw e
            } catch (e: Exception) {
                awaiting.set(null)
                LOG.info("goto failed", e)
                refusal = ByDapRequests.refusalOf(e) ?: BasedPythonBundle.message("debug.jump.failed")
            }
        }
        refused(refusal ?: BasedPythonBundle.message("debug.jump.failed"))
    }

    private companion object {
        private val LOG = Logger.getInstance(ByJumpRequests::class.java)
    }
}

/**
 * Run | Jump To Cursor in a `.by` debug session: the paused frame goes on from the caret's line.
 *
 * Hidden in any other session, as PyCharm's own is outside a Python one, and disabled while the
 * program runs or when the session cannot move a frame ([ByJumps.supported]) — bpd answers a running
 * program's threads and breakpoints now, but a frame can only be moved while its thread is held.
 */
class ByJumpToCursorAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val session = session(e)
        val jumps = (session?.debugProcess as? ByDapXDebugProcess)?.jumps
        e.presentation.isVisible = jumps != null
        e.presentation.isEnabled = jumps != null && jumps.supported && session.isSuspended && caret(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val jumps = (session(e)?.debugProcess as? ByDapXDebugProcess)?.jumps ?: return
        val (editor, file) = caret(e) ?: return
        jumps.jump(file, editor.caretModel.logicalPosition.line) { message ->
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed) HintManager.getInstance().showErrorHint(editor, message)
            }
        }
    }

    private fun session(e: AnActionEvent): XDebugSession? =
        e.project?.let { XDebuggerManager.getInstance(it).currentSession }

    private fun caret(e: AnActionEvent): Pair<Editor, VirtualFile>? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return editor to file
    }
}
