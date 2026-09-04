package dev.basedpython.pycharm.highlight

import dev.basedpython.pycharm.lang.StringLiteralShape

/** A half-open `[start, end)` range within a string literal's raw token text. */
data class EscapeRange(val startOffset: Int, val endOffset: Int)

/**
 * Finds the escape sequences in a Python/basedpython string literal, as pure logic.
 *
 * This is one of the few colouring jobs the `by` server does not do: LSP semantic tokens report a
 * whole string literal as a single `string` token, so anything *inside* the quotes — escapes,
 * f-string interpolations — has to come from the plugin. That is why this survived the removal of
 * the annotator's guessed semantic colouring, which the server does do, and better.
 *
 * @param raw the literal exactly as the lexer produced it, prefix and quotes included
 * @return ranges relative to the start of [raw]; empty for raw strings and malformed literals
 */
fun stringEscapeRanges(raw: String): List<EscapeRange> {
    val literal = StringLiteralShape.of(raw) ?: return emptyList()
    if (literal.isRaw) return emptyList()

    val ranges = mutableListOf<EscapeRange>()
    var i = literal.contentStart
    var braceDepth = 0

    while (i < literal.contentEnd) {
        val c = raw[i]

        // Inside an f-string interpolation the text is code, not string content, so `\n` there is
        // not an escape. Track brace depth to stay out of those spans.
        if (literal.isFString) {
            if (c == '{' && i + 1 < literal.contentEnd && raw[i + 1] == '{') {
                i += 2
                continue
            }
            if (c == '}' && i + 1 < literal.contentEnd && raw[i + 1] == '}') {
                i += 2
                continue
            }
            if (c == '{') {
                braceDepth++
                i++
                continue
            }
            if (c == '}' && braceDepth > 0) {
                braceDepth--
                i++
                continue
            }
            if (braceDepth > 0) {
                i++
                continue
            }
        }

        if (c == '\\' && i + 1 < literal.contentEnd) {
            val length = escapeLength(raw, i, literal.contentEnd)
            ranges.add(EscapeRange(i, i + length))
            i += length
            continue
        }

        i++
    }
    return ranges
}

/**
 * Length of the escape sequence starting at the backslash at [start].
 *
 * An unrecognised escape still measures 2: Python treats `\q` as a literal backslash-q, and
 * colouring it is how the reader notices.
 */
private fun escapeLength(raw: String, start: Int, contentEnd: Int): Int {
    fun hexRun(count: Int): Int =
        if (start + 1 + count < contentEnd && (2..(count + 1)).all { isHex(raw[start + it]) }) count + 2 else 2

    return when (raw[start + 1]) {
        'x' -> hexRun(2)
        'u' -> hexRun(4)
        'U' -> hexRun(8)
        'N' -> {
            // \N{LATIN SMALL LETTER A}
            var k = start + 2
            if (k < contentEnd && raw[k] == '{') {
                k++
                while (k < contentEnd && raw[k] != '}') k++
                if (k < contentEnd) k - start + 1 else 2
            } else {
                2
            }
        }
        in '0'..'7' -> {
            // Up to three octal digits.
            var k = start + 2
            var digits = 1
            while (k < contentEnd && digits < 3 && raw[k] in '0'..'7') {
                k++
                digits++
            }
            k - start
        }
        else -> 2
    }
}

private fun isHex(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
