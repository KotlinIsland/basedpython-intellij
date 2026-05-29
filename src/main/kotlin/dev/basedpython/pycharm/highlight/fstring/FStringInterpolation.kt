package dev.basedpython.pycharm.highlight.fstring

import com.intellij.openapi.util.TextRange

/**
 * Pure, dependency-light helper for locating f-string `{ ... }` interpolation regions
 * inside a raw string-literal token text (e.g. `f"hello {name}"`).
 *
 * This object performs NO PSI / IDE access — every function is a pure transformation of
 * a [String] into data, which makes it exhaustively unit-testable with plain JUnit.
 *
 * Returned [TextRange]s are RELATIVE to the start of the supplied literal text (offset 0 is
 * the first character of the prefix/quote), so callers must add the PSI element's start
 * offset before annotating.
 *
 * Handled cases:
 *  - prefix detection is case-insensitive and tolerant of r/b/u combos (`f`, `F`, `rf`, `fr`, `Rf`, `bf`* …)
 *    *Note: `b` and `f` are not legal together in CPython, but we are lenient — we only require
 *    that an `f`/`F` appears in the prefix to treat it as an f-string.
 *  - `{{` and `}}` are escaped braces and are NOT interpolations.
 *  - nested braces inside an interpolation are balanced by depth (`f"{ {1:2} }"`, `f"{d['k']}"`).
 *  - format specs are included in the highlighted range (`f"{x:>10}"` highlights the whole `{...}`).
 *  - an unterminated `{` at end-of-literal is highlighted from the `{` through the end of the
 *    literal content (we choose "highlight to end" rather than "skip").
 */
object FStringInterpolation {

    /** Result of analysing a literal: whether it is an f-string and its interpolation ranges. */
    data class Analysis(
        val isFString: Boolean,
        val ranges: List<TextRange>
    ) {
        companion object {
            @JvmField
            val NOT_FSTRING: Analysis = Analysis(false, emptyList())
        }
    }

    /**
     * Returns true when [raw]'s string prefix contains an `f`/`F` flag.
     * The prefix is the run of leading letters from `{r, b, u, f}` (case-insensitive)
     * that precedes the opening quote character.
     */
    fun isFString(raw: String): Boolean {
        val end = prefixEnd(raw)
        for (i in 0 until end) {
            if (raw[i] == 'f' || raw[i] == 'F') return true
        }
        return false
    }

    /**
     * Full analysis: detects f-string-ness and computes the interpolation ranges
     * (empty when not an f-string or when there are no `{...}` regions).
     */
    fun analyze(raw: String): Analysis {
        if (!isFString(raw)) return Analysis.NOT_FSTRING
        return Analysis(true, interpolationRanges(raw))
    }

    /**
     * Computes the list of interpolation [TextRange]s (relative to [raw] start).
     * Returns an empty list when [raw] is not an f-string or contains no interpolations.
     */
    fun interpolationRanges(raw: String): List<TextRange> {
        if (!isFString(raw)) return emptyList()

        val len = raw.length
        val pEnd = prefixEnd(raw)
        if (pEnd >= len) return emptyList()

        val quote = raw[pEnd]
        if (quote != '"' && quote != '\'') return emptyList()

        val triple = pEnd + 2 < len && raw[pEnd + 1] == quote && raw[pEnd + 2] == quote
        val contentStart = pEnd + (if (triple) 3 else 1)

        // Determine where the string content ends (before the closing quote(s), if present).
        val contentEnd = computeContentEnd(raw, quote, triple, contentStart)
        if (contentStart >= contentEnd) return emptyList()

        val result = ArrayList<TextRange>()

        var i = contentStart
        var depth = 0
        var interpStart = -1

        while (i < contentEnd) {
            val c = raw[i]

            if (depth == 0) {
                when {
                    // Escaped opening brace `{{` — not an interpolation.
                    c == '{' && i + 1 < contentEnd && raw[i + 1] == '{' -> {
                        i += 2
                        continue
                    }
                    // Escaped closing brace `}}` outside any interpolation — skip.
                    c == '}' && i + 1 < contentEnd && raw[i + 1] == '}' -> {
                        i += 2
                        continue
                    }
                    // Start of an interpolation.
                    c == '{' -> {
                        depth = 1
                        interpStart = i
                        i++
                        continue
                    }
                    else -> {
                        i++
                        continue
                    }
                }
            } else {
                // We are inside an interpolation: balance nested braces.
                when (c) {
                    '{' -> {
                        depth++
                        i++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            // interpStart .. i (inclusive of the closing brace).
                            result.add(TextRange(interpStart, i + 1))
                            interpStart = -1
                        }
                        i++
                    }
                    else -> i++
                }
            }
        }

        // Unterminated interpolation: highlight from the `{` to the end of the content.
        if (depth > 0 && interpStart >= 0) {
            result.add(TextRange(interpStart, contentEnd))
        }

        return result
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * Index of the first character that is NOT part of the string prefix.
     * The prefix is a run of leading characters from the set {r, R, b, B, u, U, f, F}.
     */
    private fun prefixEnd(raw: String): Int {
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                'r', 'R', 'b', 'B', 'u', 'U', 'f', 'F' -> i++
                else -> return i
            }
        }
        return i
    }

    /**
     * Computes the end index (exclusive) of the string's content, i.e. the position of the
     * closing quote(s). When the literal is unterminated we return the literal length so the
     * whole tail is treated as content.
     */
    private fun computeContentEnd(raw: String, quote: Char, triple: Boolean, contentStart: Int): Int {
        val len = raw.length
        if (triple) {
            // Closing is three quote chars at the very end.
            if (len - 3 >= contentStart &&
                raw[len - 1] == quote && raw[len - 2] == quote && raw[len - 3] == quote
            ) {
                return len - 3
            }
            return len
        }
        // Single-quoted: closing quote is the final char if it matches and there is content.
        if (len > contentStart && raw[len - 1] == quote) {
            return len - 1
        }
        return len
    }
}
