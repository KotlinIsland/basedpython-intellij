package dev.basedpython.pycharm.lang

/**
 * What one string literal token is made of: what its prefix declares, and where its content sits
 * inside it.
 *
 * Every offset is relative to the start of the token — the prefix, not the quote — because that is
 * what the lexer hands out and what a PSI element's own text starts at.
 *
 * One shape rather than one parser per caller. Escape colouring, f-string interpolation and
 * language injection all have to agree on where the quotes end, and three copies of "skip the
 * prefix, then one or three quotes" would not stay in agreement — the injection one would be the
 * copy that got `r"…"` wrong, and a raw string is exactly where the difference shows.
 */
data class StringLiteralShape(
    /** First character of the content, just past the opening quotes. */
    val contentStart: Int,
    /**
     * One past the last character of the content.
     *
     * For a literal the lexer stopped at the end of a line or the end of the file — the ordinary
     * state of a string being typed — this is the end of the token, and [isTerminated] is false.
     */
    val contentEnd: Int,
    /** The quote character, `"` or `'`. */
    val quote: Char,
    /** `"""` / `'''` rather than `"` / `'`, which is the only kind that may span lines. */
    val isTriple: Boolean,
    /** An `r` prefix: backslashes stand for themselves and nothing in here is an escape. */
    val isRaw: Boolean,
    /** An `f` prefix: the braces hold code, so the literal is not one run of text. */
    val isFString: Boolean,
    /** A `b` prefix: bytes, not text. */
    val isBytes: Boolean,
    /** Whether the closing quotes are actually there. */
    val isTerminated: Boolean,
) {

    /** How many characters of content there are; zero for `""`. */
    val contentLength: Int get() = contentEnd - contentStart

    companion object {

        /** The shape of [raw], or null when it is not a string literal at all. */
        fun of(raw: CharSequence): StringLiteralShape? {
            var prefixEnd = 0
            var isFString = false
            var isRaw = false
            var isBytes = false
            while (prefixEnd < raw.length) {
                when (raw[prefixEnd].lowercaseChar()) {
                    'f' -> isFString = true
                    'r' -> isRaw = true
                    'b' -> isBytes = true
                    'u' -> Unit
                    else -> break
                }
                prefixEnd++
            }
            if (prefixEnd >= raw.length) return null

            val quote = raw[prefixEnd]
            if (quote != '"' && quote != '\'') return null

            val isTriple =
                prefixEnd + 2 < raw.length && raw[prefixEnd + 1] == quote && raw[prefixEnd + 2] == quote
            val contentStart = prefixEnd + (if (isTriple) 3 else 1)
            val closing = if (isTriple) 3 else 1
            val isTerminated = raw.length >= contentStart + closing &&
                (0 until closing).all { raw[raw.length - 1 - it] == quote } &&
                !closingQuoteIsEscaped(raw, contentStart, raw.length - closing)
            val contentEnd = if (isTerminated) raw.length - closing else raw.length

            return StringLiteralShape(
                contentStart = contentStart,
                contentEnd = contentEnd,
                quote = quote,
                isTriple = isTriple,
                isRaw = isRaw,
                isFString = isFString,
                isBytes = isBytes,
                isTerminated = isTerminated,
            )
        }

        /**
         * Whether the quotes at [closingStart] are escaped, and so are content rather than the
         * close.
         *
         * `"a\"` is an unterminated literal whose last character happens to be a quote. It is told
         * apart from `"a\\"` — a terminated literal ending in a backslash — by counting the run of
         * backslashes in front: an odd run escapes what follows it, an even one is backslashes
         * standing for themselves.
         *
         * The `r` prefix makes no difference. A raw string cannot end in an odd number of
         * backslashes either — python's tokenizer still lets a backslash escape the closing quote,
         * it just also keeps the backslash in the value.
         */
        private fun closingQuoteIsEscaped(
            raw: CharSequence,
            contentStart: Int,
            closingStart: Int,
        ): Boolean {
            var backslashes = 0
            var index = closingStart - 1
            while (index >= contentStart && raw[index] == '\\') {
                backslashes++
                index--
            }
            return backslashes % 2 == 1
        }
    }
}
