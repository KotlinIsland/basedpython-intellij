package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Shared, document-text-based indentation helpers for BasedPython (.by) smart editing.
 *
 * The BasedPython PSI is intentionally flat (token-only), so all indentation logic here
 * operates purely on the [Document] text using line offsets — never composite PSI.
 */
internal object IndentLogic {

    /** One indentation level. BasedPython uses 4-space indents. */
    const val INDENT_SIZE: Int = 4
    val INDENT_UNIT: String = " ".repeat(INDENT_SIZE)

    /** True when [file] is a BasedPython `.by` file. */
    fun isBasedPython(file: PsiFile?): Boolean =
        file is BasedPythonFile || file?.fileType == BasedPythonFileType.INSTANCE

    /** Leading whitespace (spaces/tabs) of the line containing [offset] in [text], up to that line's start. */
    fun lineIndentText(text: CharSequence, lineStartOffset: Int): String {
        val sb = StringBuilder()
        var i = lineStartOffset
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c == ' ' || c == '\t') {
                sb.append(c)
                i++
            } else {
                break
            }
        }
        return sb.toString()
    }

    /**
     * Returns the trimmed content of the line (without leading indentation and trailing whitespace/newline).
     */
    fun lineContent(text: CharSequence, lineStartOffset: Int, lineEndOffset: Int): String {
        var s = lineStartOffset
        while (s < lineEndOffset && (text[s] == ' ' || text[s] == '\t')) s++
        return text.subSequence(s, lineEndOffset).toString().trimEnd()
    }

    /**
     * True when [content] (already trimmed) is a block header — i.e. ends with a colon that opens
     * a suite. Trailing line comments are tolerated (`if x:  # note`). Colons that are clearly not
     * block openers (inside brackets, dict/annotation on the same line) are heuristically excluded
     * by requiring the colon to be the last significant char.
     */
    fun isBlockHeader(content: String): Boolean {
        val stripped = stripTrailingComment(content).trimEnd()
        if (!stripped.endsWith(":")) return false
        // A lone ":" or a slice-like expression isn't a header; require some content before it.
        if (stripped.length < 2) return false
        // Exclude lines where the colon is balanced inside brackets (e.g. a dict literal spanning).
        // Best-effort: if there are unbalanced opening brackets, it's a continuation, not a header.
        return isBracketBalanced(stripped)
    }

    private fun stripTrailingComment(content: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble -> return content.substring(0, i)
            }
            i++
        }
        return content
    }

    private fun isBracketBalanced(content: String): Boolean {
        var depth = 0
        var inSingle = false
        var inDouble = false
        for (c in content) {
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                !inSingle && !inDouble && (c == '(' || c == '[' || c == '{') -> depth++
                !inSingle && !inDouble && (c == ')' || c == ']' || c == '}') -> depth--
            }
        }
        return depth <= 0
    }

    /**
     * Computes the indent string a NEW line should receive given the caret sits at [offset]
     * (typically the end of the current logical line before a newline is inserted).
     *
     * Rule: same indent as the current line, plus one extra level if the current line is a block header.
     */
    fun newLineIndent(doc: Document, offset: Int): String {
        val text = doc.charsSequence
        val lineNum = doc.getLineNumber(offset)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineEnd = doc.getLineEndOffset(lineNum)
        val baseIndent = lineIndentText(text, lineStart)
        val content = lineContent(text, lineStart, lineEnd)
        return if (isBlockHeader(content)) baseIndent + INDENT_UNIT else baseIndent
    }
}
