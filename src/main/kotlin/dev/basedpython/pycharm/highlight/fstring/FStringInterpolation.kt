package dev.basedpython.pycharm.highlight.fstring

import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lang.StringLiteralShape

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
    fun isFString(raw: String): Boolean = StringLiteralShape.of(raw)?.isFString == true

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
        val shape = StringLiteralShape.of(raw) ?: return emptyList()
        if (!shape.isFString) return emptyList()

        val contentStart = shape.contentStart
        val contentEnd = shape.contentEnd
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

}
