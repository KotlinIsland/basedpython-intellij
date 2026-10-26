package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
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
 * Read off the file's tokens as [BySemanticTokens] keeps them — one request per document revision,
 * from the daemon's background pass, shared with the other readers — and kept against the
 * modification stamp; on the EDT nothing is asked. A failure is never kept.
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
        val names = query(file) ?: return emptyList()
        byFile[virtualFile] = Cached(stamp, names)
        return names
    }

    /** Puts an answer in as though `by` had just given it, for [file] as it is now. */
    @TestOnly
    fun remember(file: PsiFile, names: List<ByLocalName>) {
        val document = file.viewProvider.document ?: return
        byFile[file.originalFile.virtualFile ?: return] = Cached(document.modificationStamp, names)
    }

    /** Read off the file's tokens ([BySemanticTokens]), which every reader shares. */
    private fun query(file: PsiFile): List<ByLocalName>? = BySemanticTokens.getInstance(file.project).of(file)?.let(::read)

    companion object {
        /** The modifier `by` puts on a name bound in a function's, a lambda's or a comprehension's scope. */
        const val FUNCTION_SCOPE = "functionScope"

        fun getInstance(project: Project): ByLocalNames = project.service()

        /** The `variable` and `parameter` tokens among [tokens] that carry `functionScope`. */
        fun read(tokens: List<ByToken>): List<ByLocalName> = tokens.mapNotNull { token ->
            if (FUNCTION_SCOPE !in token.modifiers) return@mapNotNull null
            when (token.type) {
                BasedPythonSemanticTokensMapping.TYPE_PARAMETER -> ByLocalName(token.range, isParameter = true)
                BasedPythonSemanticTokensMapping.TYPE_VARIABLE -> ByLocalName(token.range, isParameter = false)
                else -> null
            }
        }
    }
}
