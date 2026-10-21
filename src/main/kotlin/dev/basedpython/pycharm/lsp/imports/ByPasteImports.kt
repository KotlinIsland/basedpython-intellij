package dev.basedpython.pycharm.lsp.imports

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTemplateFiles
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByCopiedImport
import dev.basedpython.pycharm.lsp.ext.ByCopiedImportsParams
import dev.basedpython.pycharm.lsp.ext.ByCopiedText
import dev.basedpython.pycharm.lsp.ext.ByPastedImports
import dev.basedpython.pycharm.lsp.ext.ByPastedImportsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Range
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * A copy out of a file `by` serves: the file, and each piece copied — one per caret — with a marker
 * at the place it was copied from, which follows the edits made to the file since, a cut's included.
 */
internal class ByCopy(val file: VirtualFile, val document: Document, val pieces: List<Piece>) {
    class Piece(val text: String, val from: RangeMarker)
}

/**
 * The imports code copied from a file `by` serves needs, added where it is pasted — what Java and
 * Kotlin do on paste, under the same *Insert imports on paste* setting: *Always*, *Ask*, or *Never*.
 *
 * Both halves are `by`'s, and both are asked once the text has landed. `by/copiedImports` says which
 * names the copied text uses that the source file's global scope binds where the text came from, and
 * how to import each of them again elsewhere; `by/pastedImports` says which of those the file it
 * landed in does not bind where it landed, and the edits that import them.
 *
 * ## Nothing is asked at the copy
 *
 * The copy only notes what was copied and where from, which takes no request: copying stays as
 * instant as it was. The first question carries the copied text rather than a range of the source
 * file because a cut takes the text out of the file the moment it is copied — and since the cut is
 * a write action, the copy runs on the EDT inside it, where nothing may wait for `by` — while the
 * names in it meant what the scope at that place binds, which the cut leaves where it was.
 *
 * ## Where the copy is kept
 *
 * Not in the clipboard. What a processor collects goes into the clipboard's content, which the IDE
 * keeps in its clipboard history long after the copy — a platform holder, where an object of this
 * plugin would keep the whole plugin loaded after it was disabled. So the copy hands nothing to the
 * platform, and [ByCopies] keeps it, against the clipboard content it belongs to, weakly.
 */
internal class ByPasteImports : CopyPastePostProcessor<ByPasteImports.Copied>() {

    /** What was copied, handed from [extractTransferableData] to [processTransferableData] for one paste. */
    class Copied(val copy: ByCopy) : TextBlockTransferableData {
        override fun getFlavor(): DataFlavor? = null
    }

    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray,
    ): List<Copied> {
        val virtualFile = file.virtualFile ?: return emptyList()
        if (ByTemplateFiles.isTemplate(virtualFile) || byServerFor(file.project, virtualFile) == null) return emptyList()
        val document = editor.document
        val text = document.immutableCharSequence
        val pieces = startOffsets.indices
            .filter { startOffsets[it] < endOffsets[it] }
            .map { ByCopy.Piece(text.substring(startOffsets[it], endOffsets[it]), document.createRangeMarker(startOffsets[it], startOffsets[it])) }
        if (pieces.isEmpty()) return emptyList()
        ByCopies.getInstance().copied(pieces.joinToString("\n") { it.text }, ByCopy(virtualFile, document, pieces))
        return emptyList()
    }

    override fun extractTransferableData(content: Transferable): List<Copied> {
        if (CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE == CodeInsightSettings.NO) return emptyList()
        val copy = ByCopies.getInstance().copyOf(content) ?: return emptyList()
        return listOf(Copied(copy))
    }

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<Copied>,
    ) {
        val setting = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
        if (setting == CodeInsightSettings.NO) return
        val copy = values.firstOrNull()?.copy ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (ByTemplateFiles.isTemplate(file)) return
        val server = byServerFor(project, file) ?: return
        val modality = ModalityState.stateForComponent(editor.component)
        // after the paste: the platform reindents and reformats the pasted text once this returns,
        // and the question is about the text as it ends up
        project.service<ByPasteImportsScope>().scope.launch {
            val imports = copiedImports(server, copy)
            LOG.debug { "by/copiedImports for ${copy.file.name}: $imports" }
            if (imports.isNullOrEmpty()) return@launch
            addImports(server, file, document, bounds, imports, ask = setting == CodeInsightSettings.ASK, modality)
        }
    }

    /** Reads nothing but the two documents, which the copy and the paste have committed already. */
    override fun requiresAllDocumentsToBeCommitted(editor: Editor, project: Project): Boolean = false

    internal companion object {
        private val LOG = logger<ByPasteImports>()

        /**
         * How many times the paste's answer is asked for again when the document changed before it
         * could be applied — each change is someone typing, and the bound is there so that a paste
         * does not chase them.
         */
        private const val PASTE_ATTEMPTS = 3

        /**
         * The imports the code [copy] took needs, asked of [server] about the source file as it is
         * now: each piece's text, at the place its marker has followed the file's edits to. `null`
         * when it could not be asked or did not answer — a `by` from before `by/copiedImports`
         * answers `MethodNotFound`, which is simply no imports.
         */
        suspend fun copiedImports(server: LspClient, copy: ByCopy): List<ByCopiedImport>? {
            val params = readAction {
                val pieces = copy.pieces
                    .filter { it.from.isValid }
                    .map { ByCopiedText(it.text, getLsp4jPosition(copy.document, it.from.startOffset)) }
                if (pieces.isEmpty()) return@readAction null
                ByServerDocuments.ensureOpen(server, server.project, copy.file)
                ByCopiedImportsParams(server.getDocumentIdentifier(copy.file), pieces, ByTextHash.of(copy.document.immutableCharSequence))
            } ?: return null
            return server.awaitBy("by/copiedImports") { (it as ByServerExtensions).copiedImports(params) }.value
        }

        /**
         * Adds the imports of [imports] the code pasted at [bounds] is missing, asking the user first
         * when [ask] says to — [choose] is the asking, on the EDT. Each question names the text it is
         * asked about, and the answer's edits are applied only to that text; a document that changed
         * in between is asked about again.
         */
        suspend fun addImports(
            server: LspClient,
            file: VirtualFile,
            document: Document,
            bounds: RangeMarker,
            imports: List<ByCopiedImport>,
            ask: Boolean,
            modality: ModalityState,
            choose: (List<ByCopiedImport>) -> List<ByCopiedImport> = { ByPasteImportsDialog.choose(server.project, it) },
        ) {
            var asking = imports
            var chosen = !ask
            repeat(PASTE_ATTEMPTS) {
                val (stamp, params) = readAction {
                    if (!bounds.isValid) return@readAction null
                    ByServerDocuments.ensureOpen(server, server.project, file)
                    val range = Range(getLsp4jPosition(document, bounds.startOffset), getLsp4jPosition(document, bounds.endOffset))
                    document.modificationStamp to
                        ByPastedImportsParams(server.getDocumentIdentifier(file), range, asking, ByTextHash.of(document.immutableCharSequence))
                } ?: return
                val missing: ByPastedImports = server.awaitBy("by/pastedImports") {
                    (it as ByServerExtensions).pastedImports(params)
                }.value ?: return
                LOG.debug { "by/pastedImports for $asking at ${params.range}: $missing" }
                if (missing.imports.isEmpty() || missing.edits.isEmpty()) return

                if (!chosen) {
                    val picked = withContext(Dispatchers.EDT + modality.asContextElement()) {
                        choose(missing.imports)
                    }
                    if (picked.isEmpty()) return
                    chosen = true
                    // the edits of some of the imports are not some of the edits: ask for those alone
                    if (picked.size != missing.imports.size) {
                        asking = picked
                        return@repeat
                    }
                }

                val applied = withContext(Dispatchers.EDT + modality.asContextElement()) {
                    writeIntentReadAction { apply(server.project, document, stamp, missing) }
                }
                if (applied) return
                asking = missing.imports
            }
        }

        /** Applies [missing]'s edits to [document] as one step to undo, if it is still revision [stamp]. */
        fun apply(project: Project, document: Document, stamp: Long, missing: ByPastedImports): Boolean {
            if (project.isDisposed || document.modificationStamp != stamp) return false
            return WriteCommandAction.writeCommandAction(project)
                .withName(BasedPythonBundle.message("paste.imports.command"))
                .compute<Boolean, RuntimeException> { applyTextEdits(document, missing.edits) }
        }
    }
}

/**
 * Each copy from a file `by` serves, kept against the clipboard content the copy made, for as long as
 * the IDE keeps that content.
 *
 * A copy happens in two steps the platform does not connect: [ByPasteImports] is asked what to
 * collect before the clipboard content is made, and the content is made afterwards, from what every
 * processor collected. So the copy waits here as the pending one, and the content that arrives next
 * on the clipboard takes it — if its text is the text that was copied, which keeps a copy that never
 * reached the clipboard off whatever reaches it next.
 *
 * At the paste the content is looked up by identity, which is what *Paste from History* hands over.
 * An ordinary paste is handed something else: the system clipboard's view of the content, which on
 * macOS is the JDK's `TransferableProxy` around it — measured in PyCharm 263.5701, where every Cmd+V
 * of a copy made a moment before arrived as a proxy. So a content that is not one this kept is
 * matched to the newest entry of the IDE's clipboard history, which is the content the IDE last put
 * on the clipboard, when both hold the same text. Weakly: the clipboard history drops old content,
 * and the copy goes with it. An application service, so all of this goes when the plugin does.
 */
@Service(Service.Level.APP)
internal class ByCopies : Disposable {

    private class Pending(val text: String, val copy: ByCopy)

    private val pending = AtomicReference<Pending?>()

    private val copies = WeakHashMap<Transferable, ByCopy>()

    init {
        CopyPasteManager.getInstance().addContentChangedListener({ _, content -> arrived(content) }, this)
    }

    /** [text] was copied as [copy]; the clipboard content it makes is next. */
    fun copied(text: String, copy: ByCopy) {
        pending.set(Pending(text, copy))
    }

    /** The copy that made [content], if a copy from a file `by` serves made it. */
    fun copyOf(content: Transferable): ByCopy? {
        synchronized(copies) { copies[content] }?.let { return it }
        val newest = CopyPasteManager.getInstance().allContents.firstOrNull() ?: return null
        val copy = synchronized(copies) { copies[newest] } ?: return null
        val text = textOf(content) ?: return null
        return copy.takeIf { text == textOf(newest) }
    }

    private fun arrived(content: Transferable?) {
        val copy = pending.getAndSet(null) ?: return
        if (content == null || textOf(content) != copy.text) return
        synchronized(copies) { copies[content] = copy.copy }
    }

    private fun textOf(content: Transferable): String? =
        runCatching { content.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()

    override fun dispose() {
        synchronized(copies) { copies.clear() }
    }

    companion object {
        fun getInstance(): ByCopies = ApplicationManager.getApplication().service()
    }
}

/** Where a paste's imports are asked for: cancelled with the project, and with the plugin when it unloads. */
@Service(Service.Level.PROJECT)
internal class ByPasteImportsScope(val scope: CoroutineScope)
