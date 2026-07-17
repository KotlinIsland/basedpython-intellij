package dev.basedpython.pycharm.structure

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Folding for basedpython (.by) files. DumbAware — works without indexing.
 *
 * Folds:
 * 1. Function / class bodies  (collapsed by default: false)
 * 2. Multi-line ( / [ / {     (collapsed by default: false)
 * 3. Triple-quoted strings    (collapsed by default: false)
 * 4. Consecutive import block (collapsed by default: true)
 * 5. # region … # endregion  (collapsed by default: false)
 */
class BasedPythonFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> {
        val file = root as? BasedPythonFile ?: return FoldingDescriptor.EMPTY
        val text = file.text ?: return FoldingDescriptor.EMPTY
        val node = file.node ?: return FoldingDescriptor.EMPTY

        val descriptors = mutableListOf<FoldingDescriptor>()

        // --- 1. Function / class bodies + 4. import blocks ---
        val flatNodes = IndentScanner.buildFlat(text)
        for (sn in flatNodes) {
            when (sn.kind) {
                IndentScanner.NodeKind.FUNCTION, IndentScanner.NodeKind.CLASS -> {
                    // body starts after the header line's colon
                    val headerLineEnd = document.getLineEndOffset(document.getLineNumber(sn.startOffset))
                    val bodyEnd = sn.endOffset
                    if (bodyEnd > headerLineEnd + 1) {
                        descriptors += FoldingDescriptor(
                            node,
                            TextRange(headerLineEnd, bodyEnd - 1),
                            null,
                            "...",
                            false,
                            emptySet<Any>()
                        )
                    }
                }

                IndentScanner.NodeKind.IMPORT_BLOCK -> {
                    val blockEnd = sn.endOffset
                    val firstLineEnd = document.getLineEndOffset(document.getLineNumber(sn.startOffset))
                    if (blockEnd > firstLineEnd + 1) {
                        descriptors += FoldingDescriptor(
                            node,
                            TextRange(sn.startOffset, blockEnd - 1),
                            null,
                            "import ...",
                            true,   // collapse-by-default
                            emptySet<Any>()
                        )
                    }
                }

                else -> { /* FIELD, REGION handled separately */ }
            }
        }

        // --- 2. Multi-line ( / [ / { ---
        addBracketFolds(text, node, document, descriptors)

        // --- 3. Triple-quoted strings ---
        addTripleQuoteFolds(text, node, document, descriptors)

        // --- 5. # region / # endregion ---
        addRegionFolds(text, node, document, descriptors)

        return descriptors.toTypedArray()
    }

    // -----------------------------------------------------------------------

    private fun addBracketFolds(
        text: CharSequence,
        node: ASTNode,
        document: Document,
        out: MutableList<FoldingDescriptor>,
    ) {
        val open = charArrayOf('(', '[', '{')
        val close = charArrayOf(')', ']', '}')
        val stack = ArrayDeque<Pair<Char, Int>>() // (openChar, offset)
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            // skip strings
            if (c == '"' || c == '\'' || isStringPrefix(c, text, i)) {
                i = skipString(text, i)
                continue
            }
            // skip comments
            if (c == '#') {
                while (i < len && text[i] != '\n') i++
                continue
            }
            val openIdx = open.indexOf(c)
            if (openIdx >= 0) {
                stack.addLast(Pair(open[openIdx], i))
                i++
                continue
            }
            val closeIdx = close.indexOf(c)
            if (closeIdx >= 0 && stack.isNotEmpty() && stack.last().first == open[closeIdx]) {
                val (_, openOffset) = stack.removeLast()
                // only fold if span crosses a line boundary
                val openLine = document.getLineNumber(openOffset)
                val closeLine = document.getLineNumber(i)
                if (closeLine > openLine) {
                    val placeholder = "${text[openOffset]}...${text[i]}"
                    out += FoldingDescriptor(
                        node,
                        TextRange(openOffset, i + 1),
                        null,
                        placeholder,
                        false,
                        emptySet<Any>()
                    )
                }
            }
            i++
        }
    }

    private fun addTripleQuoteFolds(
        text: CharSequence,
        node: ASTNode,
        document: Document,
        out: MutableList<FoldingDescriptor>,
    ) {
        var i = 0
        val len = text.length
        while (i < len) {
            val c = text[i]
            // skip line comments
            if (c == '#') { while (i < len && text[i] != '\n') i++; continue }
            if (c == '"' || c == '\'') {
                // check triple
                if (i + 2 < len && text[i + 1] == c && text[i + 2] == c) {
                    val startOff = i
                    val q = c
                    i += 3
                    while (i < len) {
                        if (text[i] == '\\' && i + 1 < len) { i += 2; continue }
                        if (text[i] == q && i + 2 < len && text[i + 1] == q && text[i + 2] == q) {
                            i += 3; break
                        }
                        i++
                    }
                    val endOff = i
                    val startLine = document.getLineNumber(startOff)
                    val endLine = document.getLineNumber(endOff - 1)
                    if (endLine > startLine) {
                        val quoteStr = "$q$q$q"
                        out += FoldingDescriptor(
                            node,
                            TextRange(startOff, endOff),
                            null,
                            "$quoteStr...$quoteStr",
                            false,
                            emptySet<Any>()
                        )
                    }
                    continue
                } else {
                    // single-quoted string — skip
                    i = skipSingleQuote(text, i)
                    continue
                }
            }
            i++
        }
    }

    private fun addRegionFolds(
        text: CharSequence,
        node: ASTNode,
        document: Document,
        out: MutableList<FoldingDescriptor>,
    ) {
        val lines = text.split('\n')
        val lineOffsets = mutableListOf<Int>()
        var off = 0
        for (line in lines) {
            lineOffsets += off
            off += line.length + 1
        }

        val regionStack = ArrayDeque<Pair<String, Int>>() // (name, startOffset)
        for (idx in lines.indices) {
            val trimmed = lines[idx].trim()
            if (trimmed.startsWith("# region")) {
                val label = trimmed.removePrefix("# region").trim().ifEmpty { "region" }
                regionStack.addLast(Pair(label, lineOffsets[idx]))
            } else if (trimmed.startsWith("# endregion") && regionStack.isNotEmpty()) {
                val (label, startOff) = regionStack.removeLast()
                val endOff = minOf(lineOffsets[idx] + lines[idx].length, text.length)
                if (endOff > startOff) {
                    out += FoldingDescriptor(
                        node,
                        TextRange(startOff, endOff),
                        null,
                        "# region $label",
                        false,
                        emptySet<Any>()
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // String-skip helpers
    // -----------------------------------------------------------------------

    private fun isStringPrefix(c: Char, text: CharSequence, pos: Int): Boolean {
        if (c != 'r' && c != 'R' && c != 'b' && c != 'B' && c != 'f' && c != 'F' && c != 'u' && c != 'U') return false
        val len = text.length
        var i = pos + 1
        // allow up to 2 prefix chars
        if (i < len && (text[i] == 'r' || text[i] == 'R' || text[i] == 'b' || text[i] == 'B' ||
                        text[i] == 'f' || text[i] == 'F')) i++
        return i < len && (text[i] == '"' || text[i] == '\'')
    }

    /** Skips past any string (triple or single) starting at [start]. Returns index after string. */
    private fun skipString(text: CharSequence, start: Int): Int {
        val len = text.length
        var i = start
        // skip prefix chars
        while (i < len && isStringPrefix(text[i], text, i)) i++
        if (i >= len) return i
        val q = text[i]
        if (q != '"' && q != '\'') return i + 1
        return if (i + 2 < len && text[i + 1] == q && text[i + 2] == q) {
            skipTripleQuote(text, i, q)
        } else {
            skipSingleQuote(text, i)
        }
    }

    private fun skipTripleQuote(text: CharSequence, start: Int, q: Char): Int {
        val len = text.length
        var i = start + 3
        while (i < len) {
            if (text[i] == '\\' && i + 1 < len) { i += 2; continue }
            if (text[i] == q && i + 2 < len && text[i + 1] == q && text[i + 2] == q) return i + 3
            i++
        }
        return len
    }

    private fun skipSingleQuote(text: CharSequence, start: Int): Int {
        val len = text.length
        val q = text[start]
        var i = start + 1
        while (i < len) {
            val c = text[i]
            if (c == '\n' || c == '\r') return i
            if (c == '\\' && i + 1 < len) { i += 2; continue }
            if (c == q) return i + 1
            i++
        }
        return len
    }

    // -----------------------------------------------------------------------

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
