package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * "Matched same-keyword" highlighting for basedpython (.by) files.
 *
 * When the caret sits on a block-construct keyword (`if` / `elif` / `else`,
 * `try` / `except` / `finally` / `else`, `for` / `while` / `else`) we highlight all
 * sibling clause keywords belonging to the *same* construct at the *same* indentation level,
 * the way the platform highlights matched `if`/`else` pairs.
 *
 * This is a flat-PSI plugin (no real PSI tree), so we operate purely on the document text
 * via a cheap line-based indentation scan rooted at the keyword under the caret.
 */
class BasedPythonKeywordHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(
        editor: Editor,
        file: PsiFile
    ): HighlightUsagesHandlerBase<PsiElement>? {
        if (file !is BasedPythonFile) return null

        val text = editor.document.charsSequence
        val caret = editor.caretModel.offset
        if (caret < 0 || caret > text.length) return null

        // Identify the keyword (if any) under the caret.
        val kw = keywordAt(text, caret) ?: return null
        val group = GROUP_OF[kw.word] ?: return null

        val ranges = collectSiblingKeywordRanges(text, kw, group)
        if (ranges.size <= 1) return null

        return Handler(editor, file, ranges)
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    /** A keyword occurrence: its word and the absolute text range it spans. */
    private data class KeywordHit(val word: String, val start: Int, val end: Int, val indent: Int)

    /**
     * If the caret sits inside (or immediately after) a leading block keyword on its line,
     * return that keyword hit, else null. Only the keyword that *starts* the line content is
     * considered (these clause keywords always lead their line).
     */
    private fun keywordAt(text: CharSequence, caret: Int): KeywordHit? {
        val lineStart = lineStartOffset(text, caret)
        var i = lineStart
        var indent = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            indent += if (text[i] == '\t') 4 else 1
            i++
        }
        val wordStart = i
        while (i < text.length && isWordChar(text[i])) i++
        val wordEnd = i
        if (wordEnd <= wordStart) return null
        val word = text.subSequence(wordStart, wordEnd).toString()
        if (word !in ALL_CLAUSE_KEYWORDS) return null
        // Caret must be within (or adjacent to) the keyword token.
        if (caret < wordStart || caret > wordEnd) return null
        return KeywordHit(word, wordStart, wordEnd, indent)
    }

    /**
     * Walk outward (up and down) from the anchor line collecting leading clause keywords from
     * [group] that share the anchor's indentation. We stop scanning in a direction once we
     * leave the construct: a non-blank line at a *lower* indent than the anchor ends the
     * construct's surrounding scope; a line at the same indent whose leading word belongs to a
     * *different* construct also ends it.
     */
    private fun collectSiblingKeywordRanges(
        text: CharSequence,
        anchor: KeywordHit,
        group: Set<String>
    ): List<TextRange> {
        val hits = ArrayList<TextRange>(4)
        hits += TextRange(anchor.start, anchor.end)

        // Scan downward from the line after the anchor.
        scan(text, lineAfter(text, anchor.start), +1, anchor.indent, group, hits)
        // Scan upward from the line before the anchor.
        scan(text, lineBefore(text, anchor.start), -1, anchor.indent, group, hits)

        hits.sortBy { it.startOffset }
        return hits
    }

    private fun scan(
        text: CharSequence,
        fromLineStart: Int,
        dir: Int,
        anchorIndent: Int,
        group: Set<String>,
        out: MutableList<TextRange>
    ) {
        var ls = fromLineStart
        while (ls in 0..text.length) {
            val info = leadingWord(text, ls)
            if (info != null && !info.blank) {
                when {
                    info.indent < anchorIndent -> return // left the enclosing scope
                    info.indent == anchorIndent -> {
                        if (info.word in group) {
                            out += TextRange(info.wordStart, info.wordEnd)
                        } else if (info.word in ALL_CLAUSE_KEYWORDS) {
                            // A clause keyword of a *different* construct at the same indent
                            // ends the current chain in this direction.
                            return
                        } else {
                            // Any other same-indent statement ends the chain.
                            return
                        }
                    }
                    // info.indent > anchorIndent -> body line; keep scanning.
                }
            }
            ls = if (dir > 0) lineAfter(text, ls) else lineBefore(text, ls)
            if (ls < 0) return
        }
    }

    private data class LeadInfo(
        val blank: Boolean,
        val indent: Int,
        val word: String,
        val wordStart: Int,
        val wordEnd: Int
    )

    private fun leadingWord(text: CharSequence, lineStart: Int): LeadInfo? {
        if (lineStart < 0 || lineStart > text.length) return null
        var i = lineStart
        var indent = 0
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            indent += if (text[i] == '\t') 4 else 1
            i++
        }
        // End of line / file with no content => blank.
        if (i >= text.length || text[i] == '\n' || text[i] == '\r') {
            return LeadInfo(blank = true, indent = indent, word = "", wordStart = i, wordEnd = i)
        }
        // Comment-only lines are treated as blank (part of any surrounding body).
        if (text[i] == '#') {
            return LeadInfo(blank = true, indent = indent, word = "", wordStart = i, wordEnd = i)
        }
        val ws = i
        while (i < text.length && isWordChar(text[i])) i++
        val we = i
        val word = if (we > ws) text.subSequence(ws, we).toString() else ""
        return LeadInfo(blank = false, indent = indent, word = word, wordStart = ws, wordEnd = we)
    }

    // -------------------------------------------------------------------------
    // Line navigation helpers
    // -------------------------------------------------------------------------

    private fun lineStartOffset(text: CharSequence, offset: Int): Int {
        var i = (offset - 1).coerceAtMost(text.length - 1)
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }

    /** Start offset of the line following the line that contains [offset]; -1 if none. */
    private fun lineAfter(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i < text.length && text[i] != '\n') i++
        return if (i < text.length) i + 1 else -1
    }

    /** Start offset of the line preceding the line that starts at [lineStart]; -1 if none. */
    private fun lineBefore(text: CharSequence, lineStart: Int): Int {
        if (lineStart <= 0) return -1
        // lineStart-1 is the '\n' ending the previous line (or part of it).
        var i = lineStart - 1
        if (i >= 0 && text[i] == '\n') i--
        while (i >= 0 && text[i] != '\n') i--
        return i + 1
    }

    private fun isWordChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit()

    // -------------------------------------------------------------------------
    // Handler
    // -------------------------------------------------------------------------

    private class Handler(
        editor: Editor,
        private val file: PsiFile,
        private val ranges: List<TextRange>
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

        override fun getTargets(): List<PsiElement> = listOf(file)

        override fun selectTargets(targets: List<PsiElement>, selectionConsumer: Consumer<in List<PsiElement>>) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: List<PsiElement>) {
            for (r in ranges) {
                myReadUsages.add(r)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Keyword groups
    // -------------------------------------------------------------------------

    companion object {
        private val IF_GROUP = setOf("if", "elif", "else")
        private val TRY_GROUP = setOf("try", "except", "finally", "else")
        private val LOOP_GROUP = setOf("for", "while", "else")

        /**
         * Map each clause keyword to the group it anchors. `else` is ambiguous (it belongs to
         * if/try/loop constructs); we map it to the if-group as a stable default — the scan then
         * gathers whatever sibling clause keywords actually surround it at the same indent.
         */
        private val GROUP_OF: Map<String, Set<String>> = buildMap {
            put("if", IF_GROUP)
            put("elif", IF_GROUP)
            put("try", TRY_GROUP)
            put("except", TRY_GROUP)
            put("finally", TRY_GROUP)
            put("for", LOOP_GROUP)
            put("while", LOOP_GROUP)
            // `else` defaults to the union so it links to whichever construct it follows.
            put("else", IF_GROUP + TRY_GROUP + LOOP_GROUP)
        }

        private val ALL_CLAUSE_KEYWORDS: Set<String> = IF_GROUP + TRY_GROUP + LOOP_GROUP
    }
}
