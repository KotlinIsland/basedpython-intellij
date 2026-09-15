package dev.basedpython.pycharm.editor.mover

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Move Statement Up/Down for basedpython (`.by`) files: a statement swaps places with the sibling
 * statement next to it, its whole suite and decorators travelling with it.
 *
 * What a statement is, and which are siblings, is `by`'s parse — see [ByOutlines]. A line that
 * merely ends in `:` is not a header (a docstring's `Args:` is not), and a line that does not end
 * in one can be (`if x: return 1`, a header with a trailing comment).
 *
 * The unit moved:
 *  - with the caret on a statement — anywhere in a simple one, or on the header or decorators of a
 *    compound one — that statement;
 *  - with the caret on an `elif`, `except` or `case`, that clause, among the adjacent clauses of
 *    the same kind: reordering those leaves the statement valid, and no other clause can move;
 *  - with a selection, the run of sibling statements it spans exactly.
 *
 * At either end of its suite a unit has nowhere to go without being re-indented into a different
 * block, and the move is refused rather than made into a different program.
 *
 * Everything else — a blank or comment line, an `else:` line, a selection that cuts a statement,
 * and any press made before the server has answered for the text on screen — is the platform's
 * line mover. This runs on the EDT and does not wait for the server, and does not guess instead.
 */
class BasedPythonStatementMover : StatementUpDownMover() {

    override fun checkAvailable(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean): Boolean {
        if (file !is BasedPythonFile) return false
        val document = editor.document
        val outline = ByOutlines.getInstance(file.project).current(document) ?: return false

        val lines = Lines(document)
        val unit = if (editor.selectionModel.hasSelection()) {
            val selection = getLineRangeFromSelection(editor)
            lines.spanning(outline.statements, selection.startLine, selection.endLine - 1)
        } else {
            lines.unitAt(outline.statements, editor.caretModel.logicalPosition.line)
        } ?: return false

        val moved = unit.lines(unit.from, unit.to)
        info.toMove = LineRange(moved.first, moved.last + 1)
        val neighbour = if (down) unit.to + 1 else unit.from - 1
        if (neighbour !in unit.siblings.indices) return info.prohibitMove()
        val target = unit.expand(neighbour, neighbour)
        val targetLines = unit.lines(target.first, target.last)

        info.toMove2 = if (down) {
            LineRange(moved.last + 1, targetLines.last + 1)
        } else {
            LineRange(targetLines.first, moved.first)
        }
        // Siblings share an indentation, so nothing is re-indented.
        info.indentTarget = false
        return true
    }

    /**
     * Some adjacent items of one list of siblings — statements of one suite, or like clauses of one
     * statement — from [from] to [to] inclusive.
     */
    private class Unit(
        val siblings: List<TextRange>,
        val lines: Lines,
        from: Int,
        to: Int,
    ) {
        val from: Int
        val to: Int

        init {
            val expanded = expand(from, to)
            this.from = expanded.first
            this.to = expanded.last
        }

        /**
         * [from]..[to] grown to take in any sibling sharing a line with it — `a = 1; b = 2` is two
         * statements and one line, and a line cannot be moved in halves.
         */
        fun expand(from: Int, to: Int): IntRange {
            var first = from
            var last = to
            while (first > 0 && lines.last(siblings[first - 1]) >= lines.first(siblings[first])) first--
            while (last < siblings.size - 1 && lines.first(siblings[last + 1]) <= lines.last(siblings[last])) last++
            return first..last
        }

        /** The lines the siblings [from]..[to] cover. */
        fun lines(from: Int, to: Int): IntRange = lines.first(siblings[from])..lines.last(siblings[to])
    }

    private class Lines(private val document: Document) {

        fun first(range: TextRange): Int = document.getLineNumber(range.startOffset)
        fun last(range: TextRange): Int = document.getLineNumber(range.endOffset)

        /** The unit a caret on [line] moves, searching [statements] and what is nested in them. */
        fun unitAt(statements: List<ByOutline.Statement>, line: Int): Unit? {
            for ((index, statement) in statements.withIndex()) {
                if (line !in first(statement.range)..last(statement.range)) continue
                for ((clauseIndex, clause) in statement.clauses.withIndex()) {
                    val headerEnd = clause.colon ?: clause.keyword ?: TextRange.from(clause.range.startOffset, 0)
                    if (line in first(clause.range)..last(headerEnd)) {
                        return if (clauseIndex == 0) {
                            Unit(statements.map { it.range }, this, index, index)
                        } else {
                            clauseUnit(statement.clauses, clauseIndex)
                        }
                    }
                    if (line in first(clause.range)..last(clause.range)) return unitAt(clause.body, line)
                }
                // A simple statement, or the decorators above a compound one's first clause.
                return Unit(statements.map { it.range }, this, index, index)
            }
            return null
        }

        /** The run of sibling statements covering exactly lines [startLine]..[endLine]. */
        fun spanning(statements: List<ByOutline.Statement>, startLine: Int, endLine: Int): Unit? {
            val from = statements.indexOfFirst { first(it.range) == startLine }
            val to = statements.indexOfLast { last(it.range) == endLine }
            if (from >= 0 && to >= from) {
                val unit = Unit(statements.map { it.range }, this, from, to)
                // Grown past the selection by a statement sharing its first or last line: that is
                // not the run that was selected.
                return unit.takeIf { it.from == from && it.to == to }
            }
            val enclosing = statements.firstOrNull {
                startLine >= first(it.range) && endLine <= last(it.range)
            } ?: return null
            val clause = enclosing.clauses.firstOrNull { clause ->
                clause.body.isNotEmpty() &&
                    startLine >= first(clause.body.first().range) &&
                    endLine <= last(clause.body.last().range)
            } ?: return null
            return spanning(clause.body, startLine, endLine)
        }

        /**
         * The clause at [index] among the clauses next to it that share its keyword, when that
         * keyword is one whose clauses can be reordered.
         */
        private fun clauseUnit(clauses: List<ByOutline.Clause>, index: Int): Unit? {
            val keyword = keywordOf(clauses[index]) ?: return null
            if (keyword !in REORDERABLE) return null
            var first = index
            var last = index
            while (first > 0 && keywordOf(clauses[first - 1]) == keyword) first--
            while (last < clauses.size - 1 && keywordOf(clauses[last + 1]) == keyword) last++
            return Unit(clauses.subList(first, last + 1).map { it.range }, this, index - first, index - first)
        }

        private fun keywordOf(clause: ByOutline.Clause): String? =
            clause.keyword?.let { document.immutableCharSequence.subSequence(it.startOffset, it.endOffset).toString() }
    }

    private companion object {
        /** Clause keywords whose adjacent clauses can trade places and leave a valid statement. */
        val REORDERABLE = setOf("elif", "except", "case")
    }
}
