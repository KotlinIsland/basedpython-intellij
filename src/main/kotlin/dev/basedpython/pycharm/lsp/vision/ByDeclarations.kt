package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentSymbolParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly
import java.util.Collections

/**
 * The declarations of each `.by` document, as `by`'s document symbols have them, per revision: every
 * class, function, method and property, at any depth — what [ByVcsCodeVisionContext] puts a code
 * author above.
 *
 * The plugin's PSI is a token per leaf, so it has no element a declaration is; `by`'s outline is
 * where the declarations are, the one the Structure view shows.
 *
 * ## Revisions
 *
 * Kept against the `Document.modificationStamp` asked at, and handed out only for that stamp: the
 * offsets mean nothing against other text. The request names that text ([ByTextHash]), so `by`
 * answers about exactly it. Unlike a count, a document's declarations depend on nothing but its text.
 *
 * ## Which thread asks
 *
 * Off the EDT, [forFile] asks and waits — inside the platform's cancellable read action computing
 * the code author, which a keystroke cancels. On the EDT nothing waits: it is the answer for this
 * revision, or none.
 *
 * ## Asked before anyone can answer
 *
 * The code author is worked out as soon as an editor opens, which on opening a project is before
 * `by` has started. The platform works it out again on an edit, not when a server starts, so a file
 * given nothing has its code vision worked out again when `by` initializes.
 */
@Service(Service.Level.PROJECT)
internal class ByDeclarations(private val project: Project) : Disposable {

    /** What `by` said about a document at [stamp]: its declarations by the offset each starts at. */
    class Answer(val stamp: Long, private val byStart: Map<Int, Declaration>) {
        /** The declaration starting at [offset], its decorators included, if one does. */
        fun startingAt(offset: Int): Declaration? = byStart[offset]
    }

    /**
     * A declaration: [range] is all of it, decorators included; [effectiveRange] runs from its name
     * to its end, which is the code its author is worked out from, as the platform does for a named
     * element of any language.
     */
    data class Declaration(val range: TextRange, val effectiveRange: TextRange)

    /** Weakly keyed by file: a file closed and collected takes its answer with it. */
    private val answers: MutableMap<VirtualFile, Answer> = ContainerUtil.createConcurrentWeakMap()

    /** Files given no answer because nobody could give one. */
    private val servedNothing: MutableSet<VirtualFile> =
        Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap())

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /** A restarted server may be a different `by`, whose outline is the one that counts. */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    answers.clear()
                    val files = servedNothing.toList()
                    servedNothing.clear()
                    for (file in files) recompute(file)
                }
            },
        )
    }

    /**
     * The declarations of [file] as it is now, or `null` when no server answered. Off the EDT this
     * asks and waits when there is no answer for this revision yet. Inside a read action, so the text
     * cannot move under the answer.
     */
    fun forFile(file: PsiFile): Answer? {
        val original = file.originalFile
        val virtualFile = original.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return null
        answers[virtualFile]?.takeIf { it.stamp == document.modificationStamp }?.let { return it }
        if (ApplicationManager.getApplication().isDispatchThread) return servedNothing(virtualFile)

        val server = runningByServer(project, virtualFile) ?: return servedNothing(virtualFile)
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val params = ByNamedDocumentSymbolParams(
            TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri),
            ByTextHash.of(document.immutableCharSequence),
        )
        val answer = runBlockingCancellable {
            server.awaitBy("textDocument/documentSymbol", TIMEOUT_MS) { it.textDocumentService.documentSymbol(params) }
        }
        val symbols = when (answer) {
            is ByAnswer.Answer -> answer.value.mapNotNull { it.right }
            // declined: language services are off, and there is nothing to put an author above
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return servedNothing(virtualFile)
        }
        return read(document, symbols).also { answers[virtualFile] = it }
    }

    /** Puts in an answer for [document] as it is now, as though `by` had just given [symbols]. */
    @TestOnly
    fun remember(document: Document, symbols: List<DocumentSymbol>) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        answers[file] = read(document, symbols)
    }

    private fun servedNothing(file: VirtualFile): Answer? {
        servedNothing.add(file)
        return null
    }

    /** Works out [file]'s code vision again, now that there may be someone to ask. */
    private fun recompute(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@invokeLater
                val host = project.service<CodeVisionHost>()
                for (editor in EditorFactory.getInstance().getEditors(document, project)) {
                    host.invalidateProvider(CodeVisionHost.LensInvalidateSignal(editor))
                }
            },
            project.disposed,
        )
    }

    override fun dispose() {
        answers.clear()
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /** As long as `by` holds a request for text it has not been sent yet. */
        private const val TIMEOUT_MS = 10_000L

        /** The symbols that are declarations, which a code author is put above. */
        private val DECLARATION_KINDS = setOf(
            SymbolKind.Class,
            SymbolKind.Function,
            SymbolKind.Method,
            SymbolKind.Constructor,
            // a basedpython property with `get` and `set` blocks, one declaration in the source
            SymbolKind.Property,
        )

        fun getInstance(project: Project): ByDeclarations = project.service()

        /** [symbols], `by`'s hierarchical outline of [document] as it is now, as an [Answer]. */
        internal fun read(document: Document, symbols: List<DocumentSymbol>): Answer {
            val byStart = HashMap<Int, Declaration>()
            fun visit(symbol: DocumentSymbol) {
                if (symbol.kind in DECLARATION_KINDS) {
                    val start = symbol.range?.start?.let { getOffsetInDocument(document, it) }
                    val end = symbol.range?.end?.let { getOffsetInDocument(document, it) }
                    val name = symbol.selectionRange?.start?.let { getOffsetInDocument(document, it) }
                    if (start != null && end != null && name != null && start <= name && name <= end) {
                        byStart[start] = Declaration(TextRange(start, end), TextRange(name, end))
                    }
                }
                symbol.children.orEmpty().forEach(::visit)
            }
            symbols.forEach(::visit)
            return Answer(document.modificationStamp, byStart)
        }
    }
}
