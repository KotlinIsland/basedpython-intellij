package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByAlignmentGroupsParams
import dev.basedpython.pycharm.lsp.ext.ByAlignmentMember
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * basedpython's inlay hints: the `by` server's `textDocument/inlayHint` reply, drawn in the editor's
 * own font (see [ByInlayHintPresentation]).
 *
 * The platform already fetches and draws LSP inlay hints on its own, and that path is switched off
 * for `by` in `ByLspServerDescriptor` (`LspInlayHintDisabled`) so the two do not both render. Only
 * the *rendering* half of it is unwanted, but the platform offers no hook for that: the presentation
 * is built inside `LspInlayHintRendering` with `PresentationFactory.smallText`, and
 * `LspInlayHintCustomizer` can only turn the feature off or filter which hints reach it. Turning it
 * off costs nothing else — the `inlayHint` client capability is advertised unconditionally, so `by`
 * still answers the request this makes.
 *
 * What is reimplemented here is small: one request per daemon run, the hints applied through the
 * platform's own inlay pass, which owns the add/remove diffing and the inlay lifecycle.
 *
 * Not shown under `Settings | Editor | Inlay Hints` on purpose ([isVisibleInSettings]). basedpython
 * already has three settings of its own — parameters, types, return types, each never / always /
 * on push — under `Settings | basedpython | Inlay hints`, and those are what this reads. A second
 * checkbox in the platform's page would be a fourth switch on the same feature, storing its state
 * somewhere else.
 */
class ByInlayHintsProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override val key: SettingsKey<NoSettings> = SettingsKey("basedpython.inlay.hints")

    override val name: String = BasedPythonBundle.message("inlay.hints.name")

    override val previewText: String? = null

    override val isVisibleInSettings: Boolean = false

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector? {
        if (file !is BasedPythonFile) return null
        val basedPython = BasedPythonSettings.getInstance(file.project)
        val modes = basedPython.inlayModes
        // Nothing switched on: don't ask the server at all, rather than ask and drop every answer.
        // A kind set to "on push" *is* switched on — its inlays are built now and drawn later.
        if (!modes.anyCollected) return null
        return ByInlayHintsCollector(modes, basedPython.inlayPushKey)
    }
}

/**
 * Asks `by` for the whole file's hints, once per pass.
 *
 * `InlayHintsPass` does not hand a collector a tree to walk down: it flattens the file with
 * `Divider` and calls [collect] on every element it produced, so a `.by` file — whose PSI is the
 * file plus one leaf per token — would mean one call per token. One range request covers the file,
 * so the call that claims [asked] does the work and every later one returns on a failed
 * compare-and-set. A fresh collector is built for each pass, so "once" is once per daemon run.
 *
 * Threading: the platform runs this inside the daemon's background read action, so blocking on the
 * server is allowed here. [LspServer.sendRequestSync] polls `ProgressManager.checkCanceled` while it
 * waits, so an edit cancels the pass rather than queueing behind it.
 *
 * **[asked] is atomic because those elements arrive on several threads at once.** The pass pushes
 * them through `JobLauncher.invokeConcurrentlyUnderProgress`, which splits the list into a chunk per
 * pool thread and forks — one collector instance, many callers. A plain `if (asked) …; asked = true`
 * is wrong twice over there: two threads can both read `false` before either writes, and a
 * non-volatile write is not published to the others at all, so a thread can go on reading `false`
 * long after the request has been made. Either way more than one thread asks `by`, and each adds the
 * whole file's hints to the shared sink — which keys hints by offset and keeps a *list*, so
 * `InlineInlayRenderer` draws both copies end to end and every hint in the file comes out twice
 * (`def f() → 1 → 1:`). Intermittent by nature, and it repairs itself on the next pass, which is
 * what made it look like the platform misbehaving. [ByInlayAudit] is the net that would catch it
 * again.
 */
private class ByInlayHintsCollector(
    private val modes: ByHintModes,
    private val pushKey: ByPushKey,
) : InlayHintsCollector {

    private val asked = AtomicBoolean(false)

    /** Identifies this collector, and so this pass, in what [ByInlayAuditLog] records. */
    private val pass = PASSES.incrementAndGet()

    /**
     * How many callers have got past [asked] — one, and counted so that the day it is two, the
     * record says so rather than the doubling having to be explained again from scratch.
     */
    private val runs = AtomicInteger(0)

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (!asked.compareAndSet(false, true)) return true
        val run = runs.incrementAndGet()

        val file = element.containingFile as? BasedPythonFile ?: return true
        val virtualFile = file.originalFile.virtualFile ?: return true
        val document = editor.document
        val server = runningByServer(file.project, virtualFile) ?: return true

        val params = InlayHintParams(
            server.getDocumentIdentifier(virtualFile),
            getLsp4jRange(document, 0, document.textLength),
        )
        val hints = server.askBy("textDocument/inlayHint", INLAY_HINT_TIMEOUT_MS) {
            it.textDocumentService.inlayHint(params)
        }.value ?: return true

        val factory = PresentationFactory(editor)
        val thread = Thread.currentThread().name
        val collected = ArrayList<ByInlayAudit.Collected>(hints.size)
        // Every hint that was built, by the offset it sits at, so that the alignment pass below can
        // find the ones standing at the end of an assignment's target. Keyed by offset and holding a
        // list, because that is what the sink does with them too.
        val drawn = HashMap<Int, MutableList<ByInlayHintPresentation>>()
        for ((index, hint) in hints.withIndex()) {
            val position = hint.position ?: continue
            // A hint whose position no longer exists: the reply raced an edit, and the pass this is
            // running in is about to be restarted against the new text anyway.
            val offset = getOffsetInDocument(document, position) ?: continue
            val label = ByInlayHints.labelOf(hint)
            if (label.isEmpty()) continue
            val shape = ByInlayHints.shapeOf(hint, label)
            val mode = modes.forShape(shape)
            // Ordinarily nothing arrives that is switched off, `by` having been told not to compute
            // it; this is what covers the window before that setting reaches a running server.
            if (!mode.isCollected) continue

            val text = ByInlayHints.truncate(label)
            val presentation = ByInlayHintPresentation(
                editor = editor,
                text = text,
                padLeft = hint.paddingLeft == true,
                padRight = hint.paddingRight == true,
                mode = mode,
                pushKey = pushKey,
            )
            // The server's own tooltip when it sent one; otherwise the untruncated text, so a hint
            // that had to be cut can still be read in full.
            val tooltip = ByInlayHints.tooltipOf(hint) ?: label.takeIf { it != text }
            sink.addInlineElement(
                offset,
                shape.relatesToPrecedingText,
                tooltip?.let { factory.withTooltip(it, presentation) } ?: presentation,
                false,
            )
            drawn.getOrPut(offset) { ArrayList(1) } += presentation
            collected += ByInlayAudit.Collected(offset, text, run, index, thread)
        }
        // Nothing is dropped for looking wrong — a hint `by` sent twice is added twice, and the
        // record is what says so. Quietly de-duplicating here would hide the bug rather than fix it,
        // and hide it in the one place that can tell it apart from the other two causes.
        ByInlayAuditLog.getInstance().record(editor, pass, virtualFile.name, document.modificationStamp, collected)

        align(server, virtualFile, editor, document, params.range, drawn, sink)
        return true
    }

    /**
     * Keeps the blocks the author lined up by hand lined up, now that hints have been put in them.
     *
     * Only worth asking when something was collected: with no hints in the file there is nothing to
     * displace a column, and [ByAlignment.layout] would return nought for every line anyway. A hint
     * in [ByHintMode.ON_PUSH] counts as collected even while it is drawing nothing — the block has to
     * be assembled *now* so that the key press only has to re-measure it.
     *
     * A group is taken whole or not at all. Its column is the maximum over every member, so a group
     * one of whose positions no longer resolves — the reply raced an edit — would be sized against
     * the wrong maximum, and is better left alone until the pass restarts.
     */
    private fun align(
        server: LspServer,
        virtualFile: VirtualFile,
        editor: Editor,
        document: Document,
        range: Range,
        drawn: Map<Int, List<ByInlayHintPresentation>>,
        sink: InlayHintsSink,
    ) {
        if (drawn.isEmpty()) return
        val params = ByAlignmentGroupsParams(
            textDocument = TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri),
            range = range,
        )
        val groups = server.askBy("by/alignmentGroups", ALIGNMENT_TIMEOUT_MS) {
            (it as ByServerExtensions).alignmentGroups(params)
        }.value ?: return

        for (group in groups) {
            val gaps = group.members.map { member -> gapIn(document, member) }
            if (gaps.size < 2 || gaps.any { it == null }) continue
            // A block with no hint anywhere in it is a block nothing has displaced, and laying it out
            // would cost every one of its lines a standing-by pixel to arrive back where it started.
            if (gaps.none { drawn.containsKey(it?.first) }) continue

            val column = ByAlignedColumn(editor)
            val spacers = ArrayList<Pair<Int, ByAlignmentSpacer>>()
            for (gap in gaps.filterNotNull()) {
                val line = document.getLineStartOffset(document.getLineNumber(gap.first))
                val seat = column.seat(
                    leadColumns = gap.first - line,
                    gapColumns = gap.second - gap.first,
                )
                val hints = drawn[gap.first].orEmpty()
                hints.forEach { seat.take(it) }
                // Every line of a block carries an inlay at its gap, hint or not — see ByAlignedColumn.
                if (hints.isEmpty()) spacers += gap.first to seat.standIn()
            }
            spacers.forEach { (offset, spacer) -> sink.addInlineElement(offset, true, spacer, false) }
            if (column.watchesPush()) ByHintPush.getInstance().watch(column)
        }
    }

    /**
     * Where one member's padding starts and ends in this document, or `null` if that is no longer a
     * run of blanks on one line.
     *
     * The positions came from the source `by` parsed, and the document may have moved on. Checking
     * rather than trusting is what keeps a stale answer from laying a block out around an `=` that
     * has been typed over.
     */
    private fun gapIn(document: Document, member: ByAlignmentMember): Pair<Int, Int>? {
        val start = getOffsetInDocument(document, member.gapStart) ?: return null
        val end = getOffsetInDocument(document, member.gapEnd) ?: return null
        if (end <= start) return null
        if (document.getLineNumber(start) != document.getLineNumber(end)) return null
        val gap = document.charsSequence.subSequence(start, end)
        return if (gap.all { it == ' ' }) start to end else null
    }

    /** The `by` server serving this file, or `null` when there is none — off, still starting, dead. */
    private fun runningByServer(project: Project, virtualFile: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(virtualFile) }

    private companion object {
        /** Numbers collectors, and so passes, for [ByInlayAuditLog]. */
        val PASSES = AtomicLong(0)

        /**
         * Bounds a server that has stopped answering. The daemon restarts this pass on the next edit,
         * so a slow reply is better dropped than waited on.
         */
        const val INLAY_HINT_TIMEOUT_MS = 2_000

        /**
         * The same bound for `by/alignmentGroups`, and for the same reason: the answer is only worth
         * having while this pass is still the current one.
         */
        const val ALIGNMENT_TIMEOUT_MS = 2_000
    }
}
