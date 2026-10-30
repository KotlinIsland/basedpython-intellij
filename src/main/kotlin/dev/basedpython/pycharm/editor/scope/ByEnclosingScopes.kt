package dev.basedpython.pycharm.editor.scope

import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lsp.outline.ByOutline

/**
 * One compound statement of a `.by` file around an offset: the [statement], the [clause] of it the
 * offset is in, and that clause's [header] — from where the clause starts to its `:`, without it.
 * The first clause's header starts where the statement does, its decorators and modifiers included.
 */
data class ByEnclosingScope(
    val statement: ByOutline.Statement,
    val clause: ByOutline.Clause,
    val header: TextRange,
) {
    /** Whether this is a `def` or a class: the statements `by` gives a name. */
    val isDefinition: Boolean get() = statement.name != null
}

/**
 * The compound statements around an offset of a `.by` file, read off `by`'s outline ([ByOutline]),
 * innermost first: what "the header of the scope the caret is in" means for anything that names it.
 *
 * A statement is around an offset when its range holds it, ends included, as a document symbol
 * holds the caret for the platform's LSP structure view; so the caret on a `def` line is in that
 * `def`. Of a statement with several clauses, the one holding the offset is the scope, an `else`
 * rather than its `if`; on a decorator it is the first. A clause with no `:` (a trailing lambda's)
 * opens no header, and is left out.
 *
 * Every offset is the outline's, so only meaningful against the text it describes.
 */
object ByEnclosingScopes {

    fun at(outline: ByOutline, offset: Int): List<ByEnclosingScope> {
        val found = mutableListOf<ByEnclosingScope>()
        var level = outline.statements
        while (true) {
            val statement = level.firstOrNull { offset in it.range.startOffset..it.range.endOffset } ?: break
            val clauses = statement.clauses
            // before the first clause is on a decorator or a modifier, which are the first clause's
            val index = if (clauses.isNotEmpty() && offset < clauses.first().range.startOffset) 0
            else clauses.indexOfFirst { offset in it.range.startOffset..it.range.endOffset }
            val clause = clauses.getOrNull(index) ?: break
            val colon = clause.colon
            if (colon != null) {
                val start = if (index == 0) statement.range.startOffset else clause.range.startOffset
                found += ByEnclosingScope(statement, clause, TextRange(start, colon.startOffset))
            }
            level = clause.body
        }
        return found.reversed()
    }
}
