package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Where a file's docstrings are, according to `by`.
 *
 * Two requests answer it — `textDocument/semanticTokens/full` for the ranges and
 * `textDocument/documentSymbol` for the names to hover — and [ByDocstringTokens] does the rest
 * without asking anything of the file's own syntax. The plugin holds no opinion about what a
 * docstring is, which is the point: the previous version of this read the token stream and decided
 * for itself, and it was wrong about every `def` shape it had not been told to expect.
 *
 * A file with no `by` server has no docstrings here, and nothing renders. That is the honest
 * consequence of the server owning the question, and it is also what makes the rendering trustworthy
 * — a rendered block always says what `by` says.
 *
 * ## Losing the race with the server, and asking again
 *
 * `by` answers no document request for a file it has not been sent `textDocument/didOpen` for —
 * *"Document … is not open in the session"* — and the client sends that asynchronously, off the
 * event that opened the file. The rendering pass, meanwhile, runs as soon as the editor appears. So
 * the first pass over a freshly opened file usually asks too early, and gets nothing.
 *
 * Nothing would ever fix that on its own. The pass is skipped entirely while the PSI modification
 * count is unchanged, so "no docstrings" computed one millisecond too early is what the file keeps
 * until something edits it — which for a read-only stub is never. That is the whole of why the
 * first version of this rendered nothing at all: not a wrong answer, an answer asked for too soon
 * and then cached.
 *
 * So a failed answer is never stored, and [ByRenderedDocsRefresher] asks the platform to run the
 * pass again the moment the client tells the server about the file — which is exactly when the
 * earlier answer became wrong.
 *
 * ## Caching and threading
 *
 * A successful result is kept on the [PsiFile] against the document's modification stamp. The
 * rendering pass runs on every PSI change and would otherwise ask twice per keystroke; with the
 * stamp it asks once per edit.
 *
 * [of] is the way in, from any thread: off the EDT it asks the server, on the EDT `runningByServer`
 * refuses and it falls back to what is already known. [cached] asks nothing at all, for the one
 * caller that only wants to know whether an answer exists yet.
 */
internal object ByDocstringSpans {

    /**
     * Bounds the wait on a server still starting up. Generous compared to a hint's, because this
     * runs once per edit rather than per docstring, and because the first pass over a freshly opened
     * file is exactly when `by` is busiest and when getting no answer is most visible.
     */
    private const val TIMEOUT_MS = 3_000

    /**
     * Kept on the [com.intellij.openapi.vfs.VirtualFile] rather than the [PsiFile]: a `PsiFile` is a
     * view that the platform is free to drop and rebuild, and an answer that disappears with it is
     * an answer the gutter control cannot find when it goes looking.
     */
    private val CACHE = Key.create<Cached>("basedpython.docstring.spans")

    private class Cached(val stamp: Long, val spans: List<ByDocstring>)

    /**
     * The file's docstrings, asking the server when the cached answer is stale or missing.
     *
     * Safe from any thread: off the EDT it asks, and on the EDT `runningByServer` refuses, so the
     * worst case is the cached answer or none — never a blocked UI or a threading assertion.
     */
    fun of(file: PsiFile): List<ByDocstring> {
        val document = file.viewProvider.document ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val stamp = document.modificationStamp
        virtualFile.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.let { return it.spans }

        // A failure is never stored: it means the server could not answer yet, and
        // `ByRenderedDocsRefresher` will have the pass ask again once it can.
        val spans = query(file) ?: return emptyList()
        virtualFile.putUserData(CACHE, Cached(stamp, spans))
        return spans
    }

    /** What was worked out last time, or nothing. Asks the server for nothing. */
    fun cached(file: PsiFile): List<ByDocstring> {
        val stamp = file.viewProvider.document?.modificationStamp ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        return virtualFile.getUserData(CACHE)?.takeIf { it.stamp == stamp }?.spans.orEmpty()
    }

    /** `null` when the server could not answer, which is not the same as a file with no docstrings. */
    private fun query(file: PsiFile): List<ByDocstring>? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        val server = runningByServer(file.project, virtualFile) ?: return null
        // A stub reached by goto-definition is not in project content, so the platform's client
        // never syncs it and every request below would come back empty. See [ByServerDocuments].
        ByServerDocuments.ensureOpen(server, file.project, virtualFile)

        val legend = server.initializeResult?.capabilities?.semanticTokensProvider?.legend ?: return null
        val stringType = legend.tokenTypes.indexOf("string")
        val documentationBit = legend.tokenModifiers.indexOf("documentation")
        if (stringType < 0 || documentationBit < 0) {
            // A `by` that does not mark its docstrings cannot be asked where they are.
            return null
        }

        val identifier = server.getDocumentIdentifier(virtualFile)
        val tokens = server.askBy("textDocument/semanticTokens/full", TIMEOUT_MS) {
            it.textDocumentService.semanticTokensFull(SemanticTokensParams(identifier))
        }.value ?: return null

        val text = file.text
        val symbols = server.askBy("textDocument/documentSymbol", TIMEOUT_MS) {
            it.textDocumentService.documentSymbol(DocumentSymbolParams(identifier))
        }.value

        return ByDocstringTokens.spans(
            text = text,
            data = tokens.data.orEmpty(),
            stringType = stringType,
            documentationBit = documentationBit,
            symbols = flatten(symbols.orEmpty(), ByDocstringTokens.lineStarts(text)),
        )
    }

    /**
     * The symbol tree as a flat list of ranges and name offsets.
     *
     * `by` answers hierarchically, so a method arrives inside its class; both are wanted, since a
     * docstring can belong to either. The deprecated flat [SymbolInformation] shape carries no
     * `selectionRange`, so its whole range stands in for the name — hover at the start of a
     * definition still resolves it.
     */
    private fun flatten(
        symbols: List<Either<SymbolInformation, DocumentSymbol>>,
        lineStarts: List<Int>,
    ): List<BySymbol> {
        val flat = mutableListOf<BySymbol>()

        fun offset(line: Int, character: Int): Int? = lineStarts.getOrNull(line)?.plus(character)

        fun add(symbol: DocumentSymbol) {
            val start = offset(symbol.range.start.line, symbol.range.start.character)
            val end = offset(symbol.range.end.line, symbol.range.end.character)
            val name = offset(symbol.selectionRange.start.line, symbol.selectionRange.start.character)
            if (start != null && end != null && name != null && start <= end) {
                flat += BySymbol(TextRange(start, end), name)
            }
            symbol.children.orEmpty().forEach(::add)
        }

        for (either in symbols) {
            when {
                either.isRight -> add(either.right)
                either.isLeft -> {
                    val range = either.left?.location?.range ?: continue
                    val start = offset(range.start.line, range.start.character) ?: continue
                    val end = offset(range.end.line, range.end.character) ?: continue
                    if (start <= end) flat += BySymbol(TextRange(start, end), start)
                }
            }
        }
        return flat
    }
}
