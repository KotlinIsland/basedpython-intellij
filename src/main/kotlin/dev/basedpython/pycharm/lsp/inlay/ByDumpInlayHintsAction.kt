package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.datatransfer.StringSelection

private val LOG = Logger.getInstance(ByDumpInlayHintsAction::class.java)

/**
 * Writes the inlay record for the current editor to `idea.log`, and puts it on the clipboard.
 *
 * The companion to [ByInlayAuditListener], for the case it cannot serve: a doubled hint that is
 * already on screen. The listener only looks when the daemon finishes, and the daemon does not run
 * again just because someone noticed something — so this is how a doubling that is *there now* gets
 * written down, before the next keystroke quietly repairs it.
 *
 * It dumps whether or not anything is wrong. A record showing one hint collected and one drawn is
 * the answer to "is it doing it right now", and is worth as much as a record of the bug.
 */
class ByDumpInlayHintsAction : AnAction() {

    /** EDT: the editor's inlays are read here, and [update] asks for the editor. */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && file?.fileType == BasedPythonFileType.INSTANCE
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val log = ByInlayAuditLog.getInstance()
        val pass = log.lastPass(editor)
        val drawn = log.drawn(editor)
        val doublings = ByInlayAudit.doublings(pass?.collected.orEmpty(), drawn)
        val report = ByInlayAudit.report(pass, drawn, doublings)

        // WARN rather than INFO so it survives a default log configuration, which is the whole point
        // of writing it: the person reading it is going to send the file to someone.
        LOG.warn(report)
        CopyPasteManager.getInstance().setContents(StringSelection(report))

        ByCli.notifyInfo(
            project,
            BasedPythonBundle.message("notification.basedPython.title"),
            if (doublings.isEmpty()) {
                BasedPythonBundle.message("inlay.audit.clean", drawn.size)
            } else {
                BasedPythonBundle.message("inlay.audit.doubled", doublings.size)
            },
        )
    }
}
