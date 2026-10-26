package dev.basedpython.pycharm.vcs

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.lang.LangDiffIgnoredRangeProvider
import com.intellij.diff.merge.LangSpecificMergeConflictResolver
import com.intellij.diff.merge.LangSpecificMergeContext
import com.intellij.diff.util.ThreeSide
import com.intellij.lang.Language
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.ByDiffIgnoredRangesParams
import dev.basedpython.pycharm.lsp.ext.ByLineSpan
import dev.basedpython.pycharm.lsp.ext.ByMergeConflict
import dev.basedpython.pycharm.lsp.ext.ByMergeConflictsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * The merge window's semantic resolution for a `.by` file: each conflict handed to `by/resolveMergeConflicts`
 * with the merge's three versions, and the text `by` answers put in its place — imports both sides
 * added, sorted as buff's isort configuration sorts them; functions, classes and methods each side
 * added; elements each side added to a display or a decorator list; sides that differ only in
 * formatting. A conflict `by` cannot resolve, as two sides that changed one statement, answers `null`
 * and stays a conflict. Which changes touch nothing in common is a question about the syntax trees,
 * so it is `by`'s; the platform asks only while *semantic.merge.conflict.resolution* is on, as it is
 * by default.
 */
internal class ByMergeConflictResolver : LangSpecificMergeConflictResolver {

    override fun isApplicable(language: Language): Boolean = language.isKindOf(BasedPythonLanguage)

    override suspend fun tryResolveMergeConflicts(context: LangSpecificMergeContext): List<CharSequence?>? {
        val server = context.project?.let(ByMerges::server) ?: return null
        val params = readAction {
            val ours = context.file(ThreeSide.LEFT) ?: return@readAction null
            val base = context.file(ThreeSide.BASE) ?: return@readAction null
            val theirs = context.file(ThreeSide.RIGHT) ?: return@readAction null
            ByMergeConflictsParams(
                base = base.text,
                ours = ours.text,
                theirs = theirs.text,
                conflicts = context.lineFragmentList.map { fragment ->
                    ByMergeConflict(
                        base = ByLineSpan(fragment.getStartLine(ThreeSide.BASE), fragment.getEndLine(ThreeSide.BASE)),
                        ours = ByLineSpan(fragment.getStartLine(ThreeSide.LEFT), fragment.getEndLine(ThreeSide.LEFT)),
                        theirs = ByLineSpan(fragment.getStartLine(ThreeSide.RIGHT), fragment.getEndLine(ThreeSide.RIGHT)),
                    )
                },
                uri = ByMerges.uriOf(base),
            )
        } ?: return null
        val resolved = server.awaitBy("by/resolveMergeConflicts", TIMEOUT_MS) { (it as ByServerExtensions).resolveMergeConflicts(params) }
            .value ?: return null
        if (resolved.size != params.conflicts.size) return null
        // whole lines, each ending in its line break, which the platform splits back into those lines
        return resolved
    }

    private companion object {
        /** Every conflict is a parse of three whole files. */
        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * *Ignore imports and formatting* in a diff of `.by` files: the import statements and the whitespace
 * `by/diffIgnoredRanges` says a diff may ignore in each side's text, so a reformatted call or a
 * reordered import is no difference.
 */
internal class ByDiffIgnoredRangeProvider : LangDiffIgnoredRangeProvider() {

    override fun getDescription(): String = BasedPythonBundle.message("diff.ignoredRanges.description")

    override fun accepts(project: Project, language: Language): Boolean = language.isKindOf(BasedPythonLanguage)

    override fun computeIgnoredRanges(project: Project, text: CharSequence, language: Language): List<TextRange> {
        val server = ByMerges.server(project) ?: return emptyList()
        val content = text.toString()
        val answer = server.askBy("by/diffIgnoredRanges") {
            (it as ByServerExtensions).diffIgnoredRanges(ByDiffIgnoredRangesParams(content, ByMerges.SYNTHETIC_URI))
        }.value ?: return emptyList()
        return ByMerges.offsets(content, answer.imports + answer.formatting)
    }
}

internal object ByMerges {

    /** A `.by` name for texts that are no file `by` can see, so they are parsed as basedpython. */
    const val SYNTHETIC_URI = "file:///untitled.by"

    /** The project's running `by`. */
    fun server(project: Project): LspClient? =
        LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    /** The file [psi], a version of it in a merge, is a version of, as a URI; a `.by` name of its own when there is none. */
    fun uriOf(psi: PsiFile): String {
        val file: VirtualFile? = psi.viewProvider.virtualFile.let { (it as? LightVirtualFile)?.originalFile ?: it }
        return file?.takeIf { it.isInLocalFileSystem }?.url ?: SYNTHETIC_URI
    }

    /** [ranges] of [text] as offsets into it, each kept to the text. */
    fun offsets(text: String, ranges: List<Range>): List<TextRange> {
        val lineStarts = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        fun offset(position: Position): Int? =
            lineStarts.getOrNull(position.line)?.plus(position.character)?.takeIf { it <= text.length }
        return ranges.mapNotNull { range ->
            val start = offset(range.start) ?: return@mapNotNull null
            val end = offset(range.end) ?: return@mapNotNull null
            TextRange(start, end).takeIf { start <= end }
        }.sortedBy { it.startOffset }
    }
}
