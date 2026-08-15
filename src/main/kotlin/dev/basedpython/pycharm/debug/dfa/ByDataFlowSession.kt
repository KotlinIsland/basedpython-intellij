package dev.basedpython.pycharm.debug.dfa

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap

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
 */
@Service(Service.Level.PROJECT)
class ByDataFlowSession(private val project: Project) {

    private val findings = ConcurrentHashMap<VirtualFile, List<ByDataFlowFinding>>()

    /** What is known about `file` at the stop the program is held at now. */
    fun findingsFor(file: VirtualFile): List<ByDataFlowFinding> = findings[file] ?: emptyList()

    /** Whether anything is drawn at all, so a pass over an undebugged file leaves immediately. */
    fun isEmpty(): Boolean = findings.isEmpty()

    /**
     * Replace what is known about one file, and redraw it.
     *
     * Replace rather than merge: the previous entry describes the previous stop, and two stops of
     * one program have nothing to say to each other.
     */
    fun publish(file: VirtualFile, found: List<ByDataFlowFinding>) {
        if (found.isEmpty()) findings.remove(file) else findings[file] = found
        redraw(file)
    }

    /** Forget everything, because the program moved and none of it describes where it is now. */
    fun clear() {
        if (findings.isEmpty()) return
        val stale = findings.keys.toList()
        findings.clear()
        stale.forEach(::redraw)
    }

    /**
     * Ask the daemon to run its passes over a file again.
     *
     * The findings are drawn by an ordinary highlighting pass, so this is how a change in what is
     * known becomes a change on screen — the same mechanism an edit uses, with the same
     * cancellation and the same per-editor lifetime.
     */
    private fun redraw(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !file.isValid) return@invokeLater
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(psi)
        }, project.disposed)
    }

    companion object {
        fun getInstance(project: Project): ByDataFlowSession = project.service()
    }
}
