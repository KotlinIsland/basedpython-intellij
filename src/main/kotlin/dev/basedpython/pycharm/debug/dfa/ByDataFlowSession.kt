package dev.basedpython.pycharm.debug.dfa

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.debug.ByEditorMarks
import dev.basedpython.pycharm.settings.BasedPythonSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly

/**
 * What the current stop says about the code below it, per file.
 *
 * The whole of the feature's state, and it is deliberately small. A stop produces findings and a
 * resume throws them away: everything here describes one moment of one run, and a finding kept
 * past the stop it was taken at is a claim about a program state that has gone.
 *
 * That is also why there is no cache. Re-asking on every stop costs a round trip; keeping an
 * answer that might not still be true costs the user's trust in the one tool they are using
 * *because* they do not trust their own model of the code.
 *
 * ## one stop at a time
 *
 * Every stop is numbered, and its analysis runs as a job of this service that the next stop, a
 * resume, or the session ending cancels. Its answer is taken only if no stop has come since: an
 * analysis waits up to two round trips, and a program stepped in the meantime is somewhere else —
 * its "will not run" drawn over code that is running would be the one thing this feature must
 * never say.
 */
@Service(Service.Level.PROJECT)
class ByDataFlowSession(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val lock = Any()

    /** Under [lock]. */
    private val findings = HashMap<VirtualFile, List<ByDataFlowFinding>>()

    /** The number of the stop whose findings may be taken; anything else's are stale. Under [lock]. */
    private var current = 0L

    /** The analysis of the stop numbered [current], while it runs. Under [lock]. */
    private var running: Job? = null

    /** What the pass drew, removed here when the plugin goes; see [ByEditorMarks]. */
    internal val marks = ByEditorMarks(this)

    /** What is known about `file` at the stop the program is held at now. */
    fun findingsFor(file: VirtualFile): List<ByDataFlowFinding> = synchronized(lock) { findings[file] } ?: emptyList()

    /** Whether anything is drawn at all, so a pass over an undebugged file leaves immediately. */
    fun isEmpty(): Boolean = synchronized(lock) { findings.isEmpty() }

    /**
     * The program stopped in [file]: forget the last stop, and draw what [analyse] finds for this one.
     *
     * [analyse] runs off the EDT, and is cancelled if another stop, a resume or the session's end
     * comes first — which is also what keeps its answer from being drawn.
     *
     * @return the analysis, which ends once its answer has been taken or refused
     */
    fun stopped(file: VirtualFile, analyse: suspend () -> List<ByDataFlowFinding>): Job =
        synchronized(lock) {
            val stop = forgetUnderLock()
            scope.launch(Dispatchers.IO) {
                val found = try {
                    analyse()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("data flow at a stop in ${file.name} failed", e)
                    emptyList()
                }
                publish(stop, file, found)
            }.also { running = it }
        }

    /** Forget everything, because the program moved and none of it describes where it is now. */
    fun clear() {
        synchronized(lock) { forgetUnderLock() }
    }

    /**
     * The setting was turned on or off. Off takes every finding down now: the pass that would have
     * removed them no longer runs once its factory declines, so they are removed from the editors
     * directly rather than left for a daemon run that never comes.
     */
    fun settingChanged() {
        if (BasedPythonSettings.getInstance(project).debuggerDataFlow) return
        clear()
        ApplicationManager.getApplication().invokeLater({
            marks.editors().forEach(marks::clear)
        }, project.disposed)
    }

    /** Under [lock]: cancel the analysis in flight, drop what is drawn, and number the next stop. */
    private fun forgetUnderLock(): Long {
        running?.cancel()
        running = null
        if (findings.isNotEmpty()) {
            val stale = findings.keys.toList()
            findings.clear()
            stale.forEach(::redraw)
        }
        return ++current
    }

    /**
     * Replace what is known about one file, and redraw it — if [stop] is still the stop the
     * program is at.
     *
     * Replace rather than merge: the previous entry describes the previous stop, and two stops of
     * one program have nothing to say to each other.
     */
    private fun publish(stop: Long, file: VirtualFile, found: List<ByDataFlowFinding>) {
        synchronized(lock) {
            if (stop != current) return
            running = null
            if (found.isEmpty() && findings.remove(file) == null) return
            if (found.isNotEmpty()) findings[file] = found
        }
        redraw(file)
    }

    /** Draw [found] for [file] as the findings of a stop that has just been made; for tests of the pass. */
    @TestOnly
    fun publish(file: VirtualFile, found: List<ByDataFlowFinding>) {
        val stop = synchronized(lock) { forgetUnderLock() }
        publish(stop, file, found)
    }

    /**
     * Ask the daemon to run its passes over a file again.
     *
     * The findings are drawn by an ordinary highlighting pass, so this is how a change in what is
     * known becomes a change on screen — the same mechanism an edit uses, with the same
     * cancellation. A file with nothing left to draw has its marks taken down here as well, since
     * the pass only runs where the daemon has a reason to visit.
     */
    private fun redraw(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater
            if (findingsFor(file).isEmpty()) {
                val documents = FileDocumentManager.getInstance()
                for (editor in marks.editors()) {
                    if (editor.project == project && documents.getFile(editor.document) == file) marks.clear(editor)
                }
            }
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(psi, "basedpython data flow verdicts changed")
        }, project.disposed)
    }

    /** Cancels a stop's analysis; the marks are a child of this and are removed with it. */
    override fun dispose() {
        synchronized(lock) {
            running?.cancel()
            running = null
            findings.clear()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ByDataFlowSession::class.java)

        fun getInstance(project: Project): ByDataFlowSession = project.service()
    }
}
