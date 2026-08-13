package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.diagnostic.Logger
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
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.InlayHintParams
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = Logger.getInstance(ByInlayHintsProvider::class.java)

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
 * so the first call does the work and [asked] makes every later one a field read. A fresh collector
 * is built for each pass, so "once" is once per daemon run.
 *
 * Threading: the platform runs this inside the daemon's background read action, so blocking on the
 * server is allowed here. [LspServer.sendRequestSync] polls `ProgressManager.checkCanceled` while it
 * waits, so an edit cancels the pass rather than queueing behind it.
 */
private class ByInlayHintsCollector(
    private val modes: ByHintModes,
    private val pushKey: ByPushKey,
) : InlayHintsCollector {

    private var asked = false

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (asked) return true
        asked = true

        val file = element.containingFile as? BasedPythonFile ?: return true
        val virtualFile = file.originalFile.virtualFile ?: return true
        val document = editor.document
        val server = runningByServer(file.project, virtualFile) ?: return true

        val params = InlayHintParams(
            server.getDocumentIdentifier(virtualFile),
            getLsp4jRange(document, 0, document.textLength),
        )
        val hints = try {
            server.sendRequestSync(INLAY_HINT_TIMEOUT_MS) { it.textDocumentService.inlayHint(params) }
        } catch (e: Exception) {
            LOG.warn("inlayHint request to `by` failed", e)
            return true
        } ?: return true

        val factory = PresentationFactory(editor)
        for (hint in hints) {
            val position = hint.position ?: continue
            // A hint whose position no longer exists: the reply raced an edit, and the pass this is
            // running in is about to be restarted against the new text anyway.
            val offset = getOffsetInDocument(document, position) ?: continue
            val label = ByInlayHints.labelOf(hint)
            if (label.isEmpty()) continue
            val kind = ByInlayHints.kindOf(hint, label)
            val mode = modes[kind]
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
                ByInlayHints.relatesToPrecedingText(kind),
                tooltip?.let { factory.withTooltip(it, presentation) } ?: presentation,
                false,
            )
        }
        return true
    }

    /** The `by` server serving this file, or `null` when there is none — off, still starting, dead. */
    private fun runningByServer(project: Project, virtualFile: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(virtualFile) }

    private companion object {
        /**
         * Bounds a server that has stopped answering. The daemon restarts this pass on the next edit,
         * so a slow reply is better dropped than waited on.
         */
        const val INLAY_HINT_TIMEOUT_MS = 2_000
    }
}
