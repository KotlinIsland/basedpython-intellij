package dev.basedpython.pycharm.editor.mover

import com.intellij.codeInsight.editorActions.moveUpDown.LineRange
import com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Move Statement Up/Down support for BasedPython (`.by`) files.
 *
 * The PSI for `.by` files is flat (token-only), so block ranges are computed
 * purely from the [Document] text using indentation, mirroring the logic in
 * [dev.basedpython.pycharm.structure.IndentScanner].
 *
 * Behavior:
 *  - With a selection, or when the caret line is a *block header* (a non-blank
 *    line ending in `:`), the whole logical block (header + its more-indented
 *    body) is moved above the previous sibling block or below the next sibling
 *    block.
 *  - Otherwise a single line is moved, but a deeper-indented child block that
 *    would be straddled is skipped over as a unit so the move never breaks into
 *    the middle of a nested block.
 *
 * Whenever a safe move cannot be computed the mover returns `false`, deferring to
 * the platform's default `LineMover` rather than risk corrupting the document.
 *
 * Registered in plugin.xml as:
 *   <statementUpDownMover implementation="dev.basedpython.pycharm.editor.mover.BasedPythonStatementMover"
 *                         order="before line"/>
 */
class BasedPythonStatementMover : StatementUpDownMover() {

    override fun checkAvailable(editor: Editor, file: PsiFile, info: MoveInfo, down: Boolean): Boolean {
        if (file !is BasedPythonFile) return false

        val document = editor.document
        val lineCount = document.lineCount
        if (lineCount == 0) return false

        return try {
            computeMove(editor, document, info, down)
        } catch (_: Exception) {
            // Never corrupt the document: defer to the platform line mover.
            false
        }
    }

    // ------------------------------------------------------------------ logic

    private fun computeMove(editor: Editor, document: Document, info: MoveInfo, down: Boolean): Boolean {
        val lineCount = document.lineCount
        val selection = getLineRangeFromSelection(editor)
        val caretLine = editor.caretModel.logicalPosition.line.coerceIn(0, lineCount - 1)

        val hasSelection = editor.selectionModel.hasSelection()
        val headerLine = caretLine.takeIf { isBlockHeader(document, it) }

        // ---- whole-block move (selection or block header) ----------------
        if (hasSelection || headerLine != null) {
            val blockStart: Int
            val blockEndExclusive: Int
            if (hasSelection) {
                blockStart = selection.startLine
                blockEndExclusive = selection.endLine.coerceAtMost(lineCount)
            } else {
                blockStart = headerLine!!
                blockEndExclusive = blockBodyEndExclusive(document, blockStart)
            }
            if (blockStart < 0 || blockEndExclusive <= blockStart || blockEndExclusive > lineCount) {
                return false
            }
            return moveBlock(document, info, blockStart, blockEndExclusive, down)
                ?: false
        }

        // ---- single-line move (skip child blocks as a unit) -------------
        return moveSingleLine(document, info, caretLine, down)
    }

    /**
     * Moves the block `[start, endExclusive)` above the previous sibling block
     * (up) or below the next sibling block (down). Returns null when no safe
     * target exists.
     */
    private fun moveBlock(
        document: Document,
        info: MoveInfo,
        start: Int,
        endExclusive: Int,
        down: Boolean,
    ): Boolean? {
        val lineCount = document.lineCount
        val blockIndent = indentOf(document, start)

        if (down) {
            // Target is the sibling block starting at endExclusive.
            var targetStart = endExclusive
            // Skip intervening blank lines (they travel with the block boundary).
            while (targetStart < lineCount && isBlank(document, targetStart)) targetStart++
            if (targetStart >= lineCount) return null
            // A sibling must be at the same (or shallower) indent; if it is
            // shallower we are leaving the enclosing scope, which is still a
            // valid move target. If it is deeper, treat the contiguous deeper
            // run + its header as the sibling unit.
            val targetEnd = siblingEndExclusive(document, targetStart, blockIndent)
            if (targetEnd <= targetStart || targetEnd > lineCount) return null
            info.toMove = LineRange(start, endExclusive)
            info.toMove2 = LineRange(endExclusive, targetEnd)
            return true
        } else {
            // Target is the sibling block ending at start.
            var targetEnd = start
            while (targetEnd > 0 && isBlank(document, targetEnd - 1)) targetEnd--
            if (targetEnd <= 0) return null
            val targetStart = siblingStart(document, targetEnd - 1, blockIndent)
            if (targetStart < 0 || targetStart >= targetEnd) return null
            info.toMove = LineRange(start, endExclusive)
            info.toMove2 = LineRange(targetStart, start)
            return true
        }
    }

    /**
     * Moves a single line, but if the immediate neighbor in the move direction
     * is the header of a deeper-indented block, the whole child block is treated
     * as the swap unit so the line never lands inside it.
     */
    private fun moveSingleLine(document: Document, info: MoveInfo, line: Int, down: Boolean): Boolean {
        val lineCount = document.lineCount
        if (down) {
            val neighbor = line + 1
            if (neighbor >= lineCount) return false
            val end = if (startsDeeperBlock(document, neighbor, line)) {
                blockBodyEndExclusive(document, neighbor)
            } else {
                neighbor + 1
            }
            if (end > lineCount || end <= neighbor) return false
            info.toMove = LineRange(line, line + 1)
            info.toMove2 = LineRange(neighbor, end)
            return true
        } else {
            val neighbor = line - 1
            if (neighbor < 0) return false
            val start = if (lineIsInsideDeeperBlockEndingAt(document, neighbor, line)) {
                blockStartForLine(document, neighbor)
            } else {
                neighbor
            }
            if (start < 0 || start > neighbor) return false
            info.toMove = LineRange(line, line + 1)
            info.toMove2 = LineRange(start, neighbor + 1)
            return true
        }
    }

    // -------------------------------------------------------------- helpers

    /** A block header is a non-blank, non-comment line whose content ends in `:`. */
    private fun isBlockHeader(document: Document, line: Int): Boolean {
        val content = contentOf(document, line)
        if (content.isEmpty() || content.startsWith("#")) return false
        // Strip a trailing line comment before checking for the colon.
        val code = stripTrailingComment(content).trimEnd()
        if (!code.endsWith(":")) return false
        val firstWord = code.substringBefore('(').substringBefore(':').trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return firstWord in BLOCK_KEYWORDS || code.endsWith(":")
    }

    /** End (exclusive) of the block whose header is at [headerLine], by indentation. */
    private fun blockBodyEndExclusive(document: Document, headerLine: Int): Int {
        val lineCount = document.lineCount
        val headerIndent = indentOf(document, headerLine)
        var last = headerLine
        var i = headerLine + 1
        while (i < lineCount) {
            if (isBlank(document, i)) { i++; continue }
            if (indentOf(document, i) > headerIndent) {
                last = i
            } else {
                break
            }
            i++
        }
        return last + 1
    }

    /** Smallest line index of the block that [line] belongs to (its header). */
    private fun blockStartForLine(document: Document, line: Int): Int {
        if (isBlank(document, line)) return line
        val indent = indentOf(document, line)
        var i = line - 1
        while (i >= 0) {
            if (isBlank(document, i)) { i--; continue }
            val ind = indentOf(document, i)
            if (ind < indent) return i
            i--
        }
        return line
    }

    /** True when [candidate] is a header that opens a block deeper than [refLine]. */
    private fun startsDeeperBlock(document: Document, candidate: Int, refLine: Int): Boolean {
        if (!isBlockHeader(document, candidate)) return false
        return indentOf(document, candidate) >= indentOf(document, refLine)
    }

    /** True when [neighbor] is the last line of a deeper block whose header sits above [refLine]. */
    private fun lineIsInsideDeeperBlockEndingAt(document: Document, neighbor: Int, refLine: Int): Boolean {
        if (isBlank(document, neighbor)) return false
        return indentOf(document, neighbor) > indentOf(document, refLine)
    }

    /**
     * End (exclusive) of a sibling unit beginning at [targetStart]. If the target
     * line is a header, the whole block is consumed; if it is deeper than
     * [blockIndent] (a nested run) the contiguous deeper run is consumed; otherwise
     * just the single line.
     */
    private fun siblingEndExclusive(document: Document, targetStart: Int, blockIndent: Int): Int {
        val lineCount = document.lineCount
        if (isBlockHeader(document, targetStart)) {
            return blockBodyEndExclusive(document, targetStart)
        }
        val targetIndent = indentOf(document, targetStart)
        if (targetIndent > blockIndent) {
            var i = targetStart + 1
            var last = targetStart
            while (i < lineCount) {
                if (isBlank(document, i)) { i++; continue }
                if (indentOf(document, i) >= targetIndent) { last = i; i++ } else break
            }
            return last + 1
        }
        return targetStart + 1
    }

    /** Start line of the sibling unit ending at line [targetEnd] (inclusive). */
    private fun siblingStart(document: Document, targetEnd: Int, blockIndent: Int): Int {
        val targetIndent = indentOf(document, targetEnd)
        if (targetIndent > blockIndent) {
            // Walk up to the header of this nested block.
            var i = targetEnd
            while (i > 0) {
                if (isBlank(document, i)) { i--; continue }
                if (indentOf(document, i) <= blockIndent) return i
                i--
            }
            return 0
        }
        // Same level: a single line, unless it is the tail of a block whose
        // header lies above at the same indent.
        if (isBlank(document, targetEnd)) return targetEnd
        val headerCandidate = blockStartForLine(document, targetEnd)
        // Only collapse to the header when that header is itself a sibling
        // (same indent as blockIndent) so we swap whole sibling blocks.
        return if (headerCandidate < targetEnd && indentOf(document, headerCandidate) == blockIndent) {
            headerCandidate
        } else {
            targetEnd
        }
    }

    // ---- low-level text utilities ----------------------------------------

    private fun lineText(document: Document, line: Int): String {
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return document.getText(com.intellij.openapi.util.TextRange(start, end))
    }

    private fun contentOf(document: Document, line: Int): String = lineText(document, line).trim()

    private fun isBlank(document: Document, line: Int): Boolean = lineText(document, line).isBlank()

    private fun indentOf(document: Document, line: Int): Int {
        val text = lineText(document, line)
        var indent = 0
        for (c in text) {
            when (c) {
                ' ' -> indent += 1
                '\t' -> indent += 4
                else -> return indent
            }
        }
        return indent
    }

    private fun stripTrailingComment(content: String): String {
        var inSingle = false
        var inDouble = false
        for (i in content.indices) {
            val c = content[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble -> return content.substring(0, i)
            }
        }
        return content
    }

    companion object {
        private val BLOCK_KEYWORDS = setOf(
            "def", "async", "class", "if", "elif", "else", "for", "while",
            "with", "try", "except", "finally", "match", "case", "data",
            "frozen", "enum", "protocol", "public", "private", "abstract",
            "final", "static", "override",
        )
    }
}
