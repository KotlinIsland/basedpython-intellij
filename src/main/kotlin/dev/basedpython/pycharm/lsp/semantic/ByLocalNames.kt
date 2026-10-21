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
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.WeakHashMap

/** A name a function binds — a parameter or a local — where it is written. */
internal data class ByLocalName(val range: TextRange, val isParameter: Boolean)

/**
 * Where a file's parameters and locals are, according to `by`: its `parameter` and `variable`
 * semantic tokens that carry the `functionScope` modifier.
 *
 * Which names are locals is a question about the program — a name a function only reads may be a
 * global, one it assigns is a local unless it says `global`, a comprehension has a scope of its own
 * — and `by` answers it with the modifier, from the binding each name resolves to. This reads the
 * answer and decides nothing.
 *
 * A `by` whose legend has no `functionScope` (one from before the modifier) has no answer here,
 * and nothing is coloured: telling a local from a global is the whole of what is being asked.
 *
 * Asked once per document revision, from the daemon's background pass, and kept against the
 * modification stamp like [dev.basedpython.pycharm.docs.render.ByDocstringSpans]; on the EDT
 * nothing is asked. A failure is never kept.
 */
@Service(Service.Level.PROJECT)
internal class ByLocalNames(project: Project) : Disposable {

    private class Cached(val stamp: Long, val names: List<ByLocalName>)

    /** Weakly keyed, so a file collected takes its answer with it; a service, so unloading drops it. */
    private val byFile: MutableMap<VirtualFile, Cached> = Collections.synchronizedMap(WeakHashMap())

    init {
        // A restarted server may be a different `by`, and its answer is the one that counts.
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) = byFile.clear()
            },
        )
    }

    override fun dispose() = byFile.clear()

    /** The parameters and locals of [file] as it is now, or nothing when `by` could not say. */
    fun of(file: PsiFile): List<ByLocalName> {
        val document = file.viewProvider.document ?: return emptyList()
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        val stamp = document.modificationStamp
        byFile[virtualFile]?.takeIf { it.stamp == stamp }?.let { return it.names }
        val names = query(file, virtualFile) ?: return emptyList()
        byFile[virtualFile] = Cached(stamp, names)
        return names
    }

    /** Puts an answer in as though `by` had just given it, for [file] as it is now. */
    @TestOnly
    fun remember(file: PsiFile, names: List<ByLocalName>) {
        val document = file.viewProvider.document ?: return
        byFile[file.originalFile.virtualFile ?: return] = Cached(document.modificationStamp, names)
    }

    private fun query(file: PsiFile, virtualFile: VirtualFile): List<ByLocalName>? {
        val server = runningByServer(file.project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, file.project, virtualFile)

        val legend = server.initializeResult?.capabilities?.semanticTokensProvider?.legend ?: return null
        val variable = legend.tokenTypes.indexOf(BasedPythonSemanticTokensMapping.TYPE_VARIABLE)
        val parameter = legend.tokenTypes.indexOf(BasedPythonSemanticTokensMapping.TYPE_PARAMETER)
        val functionScope = legend.tokenModifiers.indexOf(FUNCTION_SCOPE)
        if (functionScope < 0) return emptyList()

        val text = file.text
        val params = ByNamedSemanticTokensParams(server.getDocumentIdentifier(virtualFile), ByTextHash.of(text))
        val tokens = server.askBy("textDocument/semanticTokens/full", TIMEOUT_MS) {
            it.textDocumentService.semanticTokensFull(params)
        }.value ?: return null
        return read(text, tokens.data.orEmpty(), variable, parameter, functionScope)
    }

    companion object {
        /** The modifier `by` puts on a name bound in a function's, a lambda's or a comprehension's scope. */
        const val FUNCTION_SCOPE = "functionScope"

        /** Five ints per token: line delta, start delta, length, type, modifier bits. */
        private const val STRIDE = 5

        /** Bounds the wait on a server that is busy, as the docstring spans' does. */
        private const val TIMEOUT_MS = 3_000

        fun getInstance(project: Project): ByLocalNames = project.service()

        /**
         * The tokens of [data], relative-encoded as the protocol has it, that are [variableType] or
         * [parameterType] and carry the [functionScopeBit] modifier, as ranges of [text].
         */
        fun read(text: CharSequence, data: List<Int>, variableType: Int, parameterType: Int, functionScopeBit: Int): List<ByLocalName> {
            val lineStarts = ByDocstringTokens.lineStarts(text)
            val mask = 1 shl functionScopeBit
            val found = mutableListOf<ByLocalName>()
            var line = 0
            var char = 0
            var i = 0
            while (i + STRIDE <= data.size) {
                val deltaLine = data[i]
                val deltaChar = data[i + 1]
                val length = data[i + 2]
                val type = data[i + 3]
                val modifiers = data[i + 4]
                i += STRIDE

                line += deltaLine
                char = if (deltaLine == 0) char + deltaChar else deltaChar
                if (modifiers and mask == 0) continue
                if (type != variableType && type != parameterType) continue
                val start = lineStarts.getOrNull(line)?.plus(char) ?: continue
                val end = start + length
                if (end <= text.length) found += ByLocalName(TextRange(start, end), isParameter = type == parameterType)
            }
            return found
        }
    }
}
