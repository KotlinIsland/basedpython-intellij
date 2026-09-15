package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Looks for a doubled hint every time the daemon finishes, so reproducing the bug is enough to have
 * it in the log.
 *
 * The daemon's finish is the moment to look: the inlay pass has applied by then, and it is the only
 * event that follows every way hints can change — a keystroke, a server reply arriving late, hints
 * being switched on.
 *
 * **Only in internal mode** (`-Didea.is.internal=true`). The check reads every inline inlay of the
 * editor on the EDT, after every daemon run, and it matches a hint's text inside what a renderer
 * says it is drawing — which cannot tell a hint drawn twice from one whose text happens to contain
 * another's at the same offset. That is a fair trade for someone chasing the bug, and none for
 * everyone else: an ordinary install pays the scan and gets the occasional false warning in its
 * log. *Dump Inlay Hint Record* still answers on demand anywhere.
 *
 * Not a check inside the collector: what the collector added and what the editor ended up showing
 * are different questions, and only the second one is the bug.
 */
class ByInlayAuditListener : DaemonCodeAnalyzer.DaemonListener {

    override fun daemonFinished(fileEditors: Collection<FileEditor>) {
        if (!ApplicationManager.getApplication().isInternal) return
        for (fileEditor in fileEditors) {
            if (fileEditor.file?.fileType != BasedPythonFileType.INSTANCE) continue
            val editor = (fileEditor as? TextEditor)?.editor ?: continue
            ByInlayAuditLog.getInstance().warnIfDoubled(editor)
        }
    }
}
