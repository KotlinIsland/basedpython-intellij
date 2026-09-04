package dev.basedpython.pycharm.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.LiteralTextEscaper

/**
 * The bridge between a `.by` string literal and the injected document made out of it.
 *
 * The platform builds the injected file from what [decode] appends, and maps every edit and every
 * caret position in it back through [getOffsetInHost]. So this is what decides whether the html in
 * `"<a href=\"/\">"` is read as `<a href="/">` — and whether typing inside that html lands on the
 * right characters of the literal afterwards.
 *
 * The decoding itself is [StringLiteralDecoding]; this is only the platform's shape for it.
 *
 * ## What this does not have to do
 *
 * basedpython strips the incidental indentation from a triple-quoted string the way java strips it
 * from a text block, so the text such a literal stands for is not the run of source between its
 * quotes. Nothing here knows that rule and nothing here should: `by` reports a fragment as the runs
 * that survive its own dedent — one per line — and each arrives as a separate range to decode. The
 * indentation is never part of one, so it is never decoded, and the injected fragment is exactly
 * the text the program will hold.
 */
class BasedPythonStringEscaper(
    host: BasedPythonStringLiteral,
) : LiteralTextEscaper<BasedPythonStringLiteral>(host) {

    /**
     * Where in the source each decoded character came from, and one past the end.
     *
     * Written by [decode] and read by [getOffsetInHost], which the platform only calls afterwards,
     * for the same range.
     */
    private var sourceOffsets: IntArray = EMPTY

    override fun getRelevantTextRange(): TextRange = myHost.contentRange

    /**
     * Whether the fragment is confined to one line, which only a triple-quoted literal is not.
     *
     * The platform uses this to decide whether an edit that adds a line can be written back: in a
     * single-quoted literal it cannot, because the result would not lex.
     */
    override fun isOneLine(): Boolean = myHost.shape?.isTriple != true

    override fun decode(rangeInsideHost: TextRange, outChars: StringBuilder): Boolean {
        val source = rangeInsideHost.subSequence(myHost.text)
        sourceOffsets = IntArray(source.length + 1)
        StringLiteralDecoding.decode(
            source = source,
            isRaw = myHost.shape?.isRaw == true,
            out = outChars,
            sourceOffsets = sourceOffsets,
        )
        return true
    }

    override fun getOffsetInHost(offsetInDecoded: Int, rangeInsideHost: TextRange): Int {
        if (offsetInDecoded < 0 || offsetInDecoded >= sourceOffsets.size) return -1
        val inRange = sourceOffsets[offsetInDecoded]
        return rangeInsideHost.startOffset + inRange.coerceAtMost(rangeInsideHost.length)
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}
