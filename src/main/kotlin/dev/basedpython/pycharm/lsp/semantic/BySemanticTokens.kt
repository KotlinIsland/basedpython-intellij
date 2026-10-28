package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.docs.render.ByDocstringTokens
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedSemanticTokensParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.SemanticTokensLegend
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.WeakHashMap

/** One of `by`'s semantic tokens, where it is in the text it was asked about, with its names. */
internal data class ByToken(val range: TextRange, val type: String, val modifiers: Set<String>)

/**
 * A file's semantic tokens as `by` gave them for the file as it is now — asked once per document
 * revision and read by whoever needs to know what a name *is* outside the highlighting the platform
 * draws from the same answer: which names are a function's locals ([ByLocalNames]), which are
 * declarations (the spellchecker's identifiers).
 *
 * Asked from background threads only; on the EDT `runningByServer` refuses and the answer is the
 * one already kept, or none. Kept against the modification stamp; a failure is never kept, and
 * every answer is dropped when `by` starts or stops, since a restarted server may be a different
 * `by`.
 */
@Service(Service.Level.PROJECT)
internal class BySemanticTokens(project: Project) : Disposable {

    private class Cached(val stamp: Long, val tokens: List<ByToken>) {
        /** Where each declaration starts — what the spellchecker asks once per identifier. */
        val definitionStarts: Set<Int> by lazy {
            tokens.filter { DEFINITION in it.modifiers }.mapTo(HashSet()) { it.range.startOffset }
        }
    }

    /** Weakly keyed, so a file collected takes its answer with it; a service, so unloading drops it. */
    private val byFile: MutableMap<VirtualFile, Cached> = Collections.synchronizedMap(WeakHashMap())

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName == BY_SERVER) byFile.clear()
                }

                /** What a stopped server said is no longer anyone's answer. */
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                    if (serverName == BY_SERVER) byFile.clear()
                }
            },
        )
    }

    override fun dispose() = byFile.clear()

    /** [file]'s tokens as it is now, or null when `by` could not say. */
    fun of(file: PsiFile): List<ByToken>? = cached(file)?.tokens

    /**
     * Whether the name starting at [offset] in [file] is one `by` marks `definition` — where it is
     * declared, as against used. Null when `by` could not say.
     */
    fun isDefinitionAt(file: PsiFile, offset: Int): Boolean? = cached(file)?.let { offset in it.definitionStarts }

    /** Puts an answer in as though `by` had just given it, for [file] as it is now. */
    @TestOnly
    fun remember(file: PsiFile, tokens: List<ByToken>) {
        val document = file.viewProvider.document ?: return
        byFile[file.originalFile.virtualFile ?: return] = Cached(document.modificationStamp, tokens)
    }

    private fun cached(file: PsiFile): Cached? {
        val document = file.viewProvider.document ?: return null
        val virtualFile = file.originalFile.virtualFile ?: return null
        val stamp = document.modificationStamp
        byFile[virtualFile]?.takeIf { it.stamp == stamp }?.let { return it }
        val tokens = query(file, virtualFile) ?: return null
        return Cached(stamp, tokens).also { byFile[virtualFile] = it }
    }

    private fun query(file: PsiFile, virtualFile: VirtualFile): List<ByToken>? {
        val server = runningByServer(file.project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, file.project, virtualFile)
        val legend = server.initializeResult?.capabilities?.semanticTokensProvider?.legend ?: return null
        // the text the answer's positions are read against, and so the text it is asked about
        val text = file.text
        val params = ByNamedSemanticTokensParams(server.getDocumentIdentifier(virtualFile), ByTextHash.of(text))
        val tokens = server.askBy("textDocument/semanticTokens/full", TIMEOUT_MS) {
            it.textDocumentService.semanticTokensFull(params)
        }.value ?: return null
        return decode(text, tokens.data.orEmpty(), legend)
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /** The modifier `by` puts on a name where it is declared. */
        const val DEFINITION = BasedPythonSemanticTokensMapping.MOD_DEFINITION

        /** Five ints per token: line delta, start delta, length, type, modifier bits. */
        private const val STRIDE = 5

        /** Bounds the wait on a server that is busy, as the docstring spans' does. */
        private const val TIMEOUT_MS = 3_000

        fun getInstance(project: Project): BySemanticTokens = project.service()

        /**
         * The tokens of [data], relative-encoded as the protocol has it, as ranges of [text] named
         * through [legend]. A token past the end of [text] is about some other text and is dropped.
         */
        fun decode(text: CharSequence, data: List<Int>, legend: SemanticTokensLegend): List<ByToken> {
            val lineStarts = ByDocstringTokens.lineStarts(text)
            val types = legend.tokenTypes.orEmpty()
            val modifierNames = legend.tokenModifiers.orEmpty()
            val found = mutableListOf<ByToken>()
            var line = 0
            var char = 0
            var i = 0
            while (i + STRIDE <= data.size) {
                val deltaLine = data[i]
                val deltaChar = data[i + 1]
                val length = data[i + 2]
                val type = types.getOrNull(data[i + 3])
                val bits = data[i + 4]
                i += STRIDE

                line += deltaLine
                char = if (deltaLine == 0) char + deltaChar else deltaChar
                if (type == null) continue
                val start = lineStarts.getOrNull(line)?.plus(char) ?: continue
                val end = start + length
                if (end > text.length) continue
                val modifiers = modifierNames.filterIndexedTo(HashSet()) { bit, _ -> bits and (1 shl bit) != 0 }
                found += ByToken(TextRange(start, end), type, modifiers)
            }
            return found
        }
    }
}
