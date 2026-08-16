package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Looks for a doubled hint every time the daemon finishes, so reproducing the bug is enough to have
 * it in the log.
 *
 * The daemon's finish is the moment to look: the inlay pass has applied by then, and it is the only
 * event that follows every way hints can change — a keystroke, a server reply arriving late, hints
 * being switched on. Cheap enough to run unconditionally (the inline inlays of one `.by` editor and
 * a map), and it writes nothing unless something is actually doubled.
 *
 * Not a check inside the collector: what the collector added and what the editor ended up showing
 * are different questions, and only the second one is the bug.
 */
class ByInlayAuditListener : DaemonCodeAnalyzer.DaemonListener {

    override fun daemonFinished(fileEditors: Collection<FileEditor>) {
        for (fileEditor in fileEditors) {
            if (fileEditor.file?.fileType != BasedPythonFileType.INSTANCE) continue
            val editor = (fileEditor as? TextEditor)?.editor ?: continue
            ByInlayAuditLog.getInstance().warnIfDoubled(editor)
        }
    }
}
