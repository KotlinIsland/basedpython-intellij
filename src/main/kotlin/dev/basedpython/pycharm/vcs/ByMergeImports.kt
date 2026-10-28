package dev.basedpython.pycharm.vcs

import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lsp.ext.ByImportChoice
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/** A merge's three versions of one file, as git holds them. */
internal data class ByMergeVersions(val base: String, val ours: String, val theirs: String) {
    /** Whether both sides changed the file: one side unchanged is the other side's text, nothing to reconcile. */
    val bothChanged: Boolean get() = ours != base && theirs != base
}

/**
 * The text work of reconciling a merged file's imports with what `by/reconcileMergeImports` answered:
 * its edits as replacements in a text, and the edits a picked [ByImportChoice] makes.
 */
internal object ByMergeImports {

    /** One replacement of [range] in a text by [text]. */
    data class Replacement(val range: TextRange, val text: String)

    /**
     * [edits], LSP ranges in [text], as replacements ordered so that each can be made in turn without
     * moving the next: the last first, and of two at one offset the one removing text first, so an
     * insertion there lands before what follows it. `null` when an edit reaches outside [text], which
     * means the text is not what the edits were made for.
     */
    fun replacements(text: CharSequence, edits: List<TextEdit>): List<Replacement>? {
        val lineStarts = buildList {
            add(0)
            text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
        }
        fun offset(position: Position): Int? {
            val start = lineStarts.getOrNull(position.line) ?: return null
            val end = lineStarts.getOrNull(position.line + 1) ?: text.length
            return (start + position.character).takeIf { it <= end }
        }
        return edits.map { edit ->
            val start = offset(edit.range.start) ?: return null
            val end = offset(edit.range.end) ?: return null
            if (start > end) return null
            Replacement(TextRange(start, end), edit.newText)
        }.sortedWith(compareByDescending<Replacement> { it.range.startOffset }.thenByDescending { it.range.endOffset })
    }

    /** [replacements] made in [text]. */
    fun apply(text: String, replacements: List<Replacement>): String =
        replacements.fold(StringBuilder(text)) { out, r -> out.replace(r.range.startOffset, r.range.endOffset, r.text) }.toString()

    /** The edits that put [statements], one version's imports of [choice]'s name, where [choice] says, replacing its bindings. */
    fun choiceEdits(choice: ByImportChoice, statements: List<String>): List<TextEdit> =
        choice.bindings.map { TextEdit(it, "") } +
            TextEdit(Range(choice.position, choice.position), statements.joinToString("") { "$it\n" })
}
