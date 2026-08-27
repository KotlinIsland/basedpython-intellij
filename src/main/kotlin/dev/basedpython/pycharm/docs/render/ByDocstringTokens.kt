package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.util.TextRange

/**
 * A docstring the server pointed at, and the symbol it documents.
 *
 * @param range the whole string literal, quotes and prefix included — what the editor replaces with
 *   the rendered block.
 * @param ownerNameOffset offset of the name of the symbol this docstring documents, which is the
 *   position to ask `by` about it. `null` for a module docstring, which documents the file and so
 *   has no name; see [ByRenderedDocs].
 */
internal data class ByDocstring(
    val range: TextRange,
    val ownerNameOffset: Int?,
)

/** A symbol as `textDocument/documentSymbol` reported it, flattened out of its tree. */
internal data class BySymbol(
    /** The whole definition, header and body. */
    val range: TextRange,
    /** The name alone — `selectionRange` — which is where hover answers for the symbol. */
    val nameOffset: Int,
)

/**
 * Reads a docstring out of what `by` already says about a file.
 *
 * Nothing here decides what a docstring *is*. `by` classifies one while it walks its own AST —
 * `ty_ide`'s semantic token visitor carries an `expecting_docstring` flag into module, function and
 * class bodies, and the string it finds there is emitted as a `string` token with the
 * `documentation` modifier — so the ranges are had by filtering its `textDocument/semanticTokens`
 * reply. That is the whole of the detection, and it is the server's, which is why `async def`,
 * decorated defs, overload implementations, `frozen data class`, `enum class`, `protocol`, nested
 * classes and the docstring under a `let` or an annotated field all arrive without any of them
 * being named here.
 *
 * The one thing the protocol does not say is which symbol a docstring belongs to, and hover needs a
 * name to answer for. That is arithmetic over `textDocument/documentSymbol`, whose `selectionRange`
 * is the name and whose `range` is the whole definition:
 *  - a symbol ending on the line directly above the docstring owns it — this is the docstring under
 *    `let a = 1` or `field: int = 0`, which sits after the definition rather than inside it, and it
 *    is checked first because such a symbol is also inside the class whose body it is in;
 *  - otherwise the innermost symbol whose range contains the docstring owns it — every `def` and
 *    `class`, whose docstring is the first statement of the body;
 *  - otherwise nobody does, which is the module docstring.
 *
 * LSP positions count UTF-16 code units and so do Kotlin's, so a line start plus a character is an
 * offset with nothing to convert.
 */
internal object ByDocstringTokens {

    /** Five ints per token: line delta, start delta, length, type, modifier bits. */
    private const val STRIDE = 5

    /**
     * The docstrings in [text], given the server's semantic tokens and symbols.
     *
     * @param data the flat `SemanticTokens.data` array, relative-encoded as the protocol has it.
     * @param stringType the index of `string` in the server's token-type legend.
     * @param documentationBit the index of `documentation` in its token-modifier legend.
     */
    fun spans(
        text: CharSequence,
        data: List<Int>,
        stringType: Int,
        documentationBit: Int,
        symbols: List<BySymbol>,
    ): List<ByDocstring> {
        val lineStarts = lineStarts(text)
        val ranges = merge(documentationRanges(text, data, stringType, documentationBit, lineStarts))
        if (ranges.isEmpty()) return emptyList()
        return ranges.map { ByDocstring(it, ownerNameOffset(it, symbols, lineStarts)) }
    }

    /** The offset each line begins at, so a `(line, character)` pair is one addition away. */
    fun lineStarts(text: CharSequence): List<Int> {
        val starts = ArrayList<Int>(16)
        starts += 0
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        return starts
    }

    /** The ranges of the `string` tokens carrying the `documentation` modifier, in document order. */
    private fun documentationRanges(
        text: CharSequence,
        data: List<Int>,
        stringType: Int,
        documentationBit: Int,
        lineStarts: List<Int>,
    ): List<TextRange> {
        val mask = 1 shl documentationBit
        val ranges = mutableListOf<TextRange>()
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
            if (type != stringType || modifiers and mask == 0) continue

            val start = lineStarts.getOrNull(line)?.plus(char) ?: continue
            val end = (start + length).coerceAtMost(text.length)
            if (start in 0..end) ranges += TextRange(start, end)
        }
        return ranges
    }

    /**
     * Joins the pieces of one docstring back together.
     *
     * A multi-line docstring is emitted as one token per line — the protocol has no other way to
     * say it — and what separates two of them is the newline and nothing else. Two *different*
     * docstrings always have a statement between them, since only the first statement of a body is
     * ever one, so whitespace-only is a safe join.
     */
    private fun merge(ranges: List<TextRange>): List<TextRange> {
        if (ranges.size < 2) return ranges
        val merged = mutableListOf<TextRange>()
        var current = ranges.first()
        for (next in ranges.drop(1)) {
            current = if (next.startOffset <= current.endOffset + 1 && next.startOffset >= current.endOffset) {
                TextRange(current.startOffset, next.endOffset)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    /** Which symbol's name to ask `by` about for this docstring; see the class comment. */
    private fun ownerNameOffset(
        docstring: TextRange,
        symbols: List<BySymbol>,
        lineStarts: List<Int>,
    ): Int? {
        val docstringLine = lineOf(docstring.startOffset, lineStarts)

        val preceding = symbols
            .filter { it.range.endOffset <= docstring.startOffset }
            .maxByOrNull { it.range.endOffset }
        if (preceding != null && lineOf(preceding.range.endOffset, lineStarts) == docstringLine - 1) {
            return preceding.nameOffset
        }

        return symbols
            .filter { it.range.contains(docstring) }
            .minByOrNull { it.range.length }
            ?.nameOffset
    }

    /** The line an offset falls on, by binary search over [lineStarts]. */
    private fun lineOf(offset: Int, lineStarts: List<Int>): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found else -found - 2
    }
}
