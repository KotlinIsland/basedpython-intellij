package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import java.util.WeakHashMap

private val LOG = Logger.getInstance(ByInlayAuditLog::class.java)

/**
 * What the last inlay pass added, per editor, and the one place that compares it with what the
 * editor is drawing.
 *
 * Kept so a doubled hint can be *caught* rather than reasoned about: it is intermittent (see
 * [ByInlayAudit]), it survives until the next pass, and by then everything that would say where it
 * came from is gone. One record per editor is enough — a doubling is visible for as long as it is
 * on screen, and the pass that made it is the last one to have run.
 *
 * Application-level because it is keyed by editor, not by project, and weakly because an editor is
 * closed without telling this: a record is a handful of strings and dies with the editor it is
 * about.
 *
 * Nothing here is allowed to throw at its callers. It is a diagnostic; a diagnostic that breaks the
 * daemon or an action is worse than the bug it is looking for.
 */
@Service(Service.Level.APP)
class ByInlayAuditLog {

    private val passes = WeakHashMap<Editor, ByInlayAudit.Pass>()

    /** The doublings last warned about, so one standing doubling is not logged on every daemon run. */
    private val warned = WeakHashMap<Editor, String>()

    /**
     * Records what one run of the collector added.
     *
     * Merges when [id] matches what is already there, rather than replacing: a second run in one
     * pass is the bug this is watching for, and overwriting the first run's hints would erase the
     * evidence for it. A new [id] starts fresh — that is the next pass.
     */
    fun record(editor: Editor, id: Long, file: String, docStamp: Long, collected: List<ByInlayAudit.Collected>) {
        synchronized(passes) {
            val previous = passes[editor]?.takeIf { it.id == id }
            passes[editor] = ByInlayAudit.Pass(
                id = id,
                file = file,
                docStamp = docStamp,
                runs = (previous?.runs ?: 0) + 1,
                collected = previous?.collected.orEmpty() + collected,
            )
        }
    }

    /** The last pass recorded for [editor], or `null` if none has run since it was opened. */
    fun lastPass(editor: Editor): ByInlayAudit.Pass? = synchronized(passes) { passes[editor] }

    /**
     * Every inline inlay in [editor], as its renderer describes itself.
     *
     * `toString` rather than anything structural: the platform's own renderer answers with its
     * presentations joined (`LinearOrderInlayRenderer.toString` → `SequencePresentation.toString` →
     * `[a b]`), the wrappers in between delegate, and [ByInlayHintPresentation.toString] is the
     * hint's text — so a hint drawn twice says so, through public API and no reflection.
     *
     * Not filtered to this plugin's inlays. Anything else drawing in a `.by` line is worth seeing in
     * the same list, and telling ours apart by renderer type would mean naming internal classes.
     *
     * EDT, like everything that reads the editor's model.
     */
    fun drawn(editor: Editor): List<ByInlayAudit.Drawn> =
        editor.inlayModel
            .getInlineElementsInRange(0, editor.document.textLength)
            .map { ByInlayAudit.Drawn(it.offset, it.renderer.toString()) }

    /** The full record for [editor] — the pass, what is drawn, and the verdicts. EDT. */
    fun report(editor: Editor): String {
        val pass = lastPass(editor)
        val drawn = drawn(editor)
        return ByInlayAudit.report(pass, drawn, ByInlayAudit.doublings(pass?.collected.orEmpty(), drawn))
    }

    /**
     * Writes the whole record to the log when something is doubled, once per new set of findings.
     *
     * Once per *set*, because the daemon finishes far more often than the inlay pass runs (the pass
     * factory skips a file whose PSI is unchanged), and a hint that is doubled stays doubled until
     * something re-collects it — so warning on every daemon run would fill the log with one bug.
     *
     * Returns whether it warned, which is what the tests and [ByDumpInlayHintsAction] read.
     */
    fun warnIfDoubled(editor: Editor): Boolean {
        try {
            val pass = lastPass(editor) ?: return false
            val drawn = drawn(editor)
            val doublings = ByInlayAudit.doublings(pass.collected, drawn)
            if (doublings.isEmpty()) {
                synchronized(passes) { warned.remove(editor) }
                return false
            }
            val signature = "${pass.id}:" + doublings.joinToString(";") { "${it.offset}/${it.text}/${it.cause}" }
            synchronized(passes) {
                if (warned[editor] == signature) return false
                warned[editor] = signature
            }
            LOG.warn(ByInlayAudit.report(pass, drawn, doublings))
            return true
        } catch (e: Throwable) {
            LOG.debug("inlay audit failed", e)
            return false
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): ByInlayAuditLog = ApplicationManager.getApplication().service()
    }
}
