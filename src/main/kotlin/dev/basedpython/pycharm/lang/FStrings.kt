package dev.basedpython.pycharm.lang

import com.intellij.psi.tree.IElementType

/**
 * How an f-string or t-string is written, as Python's tokenizer reads one since PEP 701 (3.12): the
 * prefix and opening quotes, runs of text, each `{...}` replacement field — an expression, a `!r`
 * conversion, a `:` format spec that may hold fields of its own — and the closing quotes.
 *
 * Lexical, and the tokenizer's rather than the parser's: a field ends at the `}` its own brackets
 * leave it at, a quote inside a field opens a string of the field's (so `f"{d["k"]}"` is one
 * literal), a field of a single-quoted literal may run over lines, and `{{` and `}}` are text. What
 * the parser makes of it — which part is an interpolation, which escape — is `by`'s outline.
 */
internal object FStrings {

    /** Whether the prefix in `[start, quoteStart)` makes the literal an f-string or t-string. */
    fun isInterpolated(buffer: CharSequence, start: Int, quoteStart: Int): Boolean =
        (start until quoteStart).any { buffer[it] == 'f' || buffer[it] == 'F' || buffer[it] == 't' || buffer[it] == 'T' }

    /**
     * Where the interpolated literal whose prefix starts at [start] ends, at most [end]: after its
     * closing quotes, or — left open — at [end], or at the line break that ends a single-quoted one.
     * Each part is handed to [emit] in order, tiling the literal.
     */
    fun scan(buffer: CharSequence, start: Int, end: Int, emit: ((Int, Int, IElementType) -> Unit)? = null): Int =
        Scanner(buffer, end, emit ?: { _, _, _ -> }).literal(start)

    private class Scanner(
        private val buffer: CharSequence,
        private val end: Int,
        private val emit: (Int, Int, IElementType) -> Unit,
    ) {
        private val tokens = BasedPythonLexer()

        /** One interpolated literal from its prefix at [start]; returns where it ends. */
        fun literal(start: Int): Int {
            var pos = start
            while (pos < end && buffer[pos].isLetter()) pos++
            if (pos >= end) return end.also { emit(start, end, BasedPythonTokenTypes.FSTRING_START) }
            val quote = buffer[pos]
            val triple = pos + 2 < end && buffer[pos + 1] == quote && buffer[pos + 2] == quote
            pos += if (triple) 3 else 1
            emit(start, pos, BasedPythonTokenTypes.FSTRING_START)
            return body(pos, quote, triple)
        }

        private fun closesAt(pos: Int, quote: Char, triple: Boolean): Boolean =
            buffer[pos] == quote && (!triple || pos + 2 < end && buffer[pos + 1] == quote && buffer[pos + 2] == quote)

        private fun endsLine(pos: Int, triple: Boolean): Boolean = !triple && (buffer[pos] == '\n' || buffer[pos] == '\r')

        /** The text and fields up to the closing quotes. */
        private fun body(from: Int, quote: Char, triple: Boolean): Int {
            var pos = from
            var text = from
            fun flush() {
                if (pos > text) emit(text, pos, BasedPythonTokenTypes.FSTRING_TEXT)
            }
            while (true) {
                if (pos >= end) return end.also { flush() }
                val c = buffer[pos]
                when {
                    endsLine(pos, triple) -> return pos.also { flush() }
                    c == '\\' && pos + 1 < end && !endsLine(pos + 1, triple) -> pos += 2
                    closesAt(pos, quote, triple) -> {
                        flush()
                        val width = if (triple) 3 else 1
                        emit(pos, pos + width, BasedPythonTokenTypes.FSTRING_END)
                        return pos + width
                    }
                    (c == '{' || c == '}') && pos + 1 < end && buffer[pos + 1] == c -> {
                        flush()
                        emit(pos, pos + 2, BasedPythonTokenTypes.FSTRING_ESCAPED_BRACE)
                        pos += 2
                        text = pos
                    }
                    c == '{' -> {
                        flush()
                        pos = field(pos, quote, triple)
                        text = pos
                    }
                    else -> pos++
                }
            }
        }

        /** A replacement field from its `{` at [from]; returns where it ends, after its `}`. */
        private fun field(from: Int, quote: Char, triple: Boolean): Int {
            emit(from, from + 1, BasedPythonTokenTypes.FSTRING_LBRACE)
            var pos = from + 1
            var depth = 0
            while (true) {
                if (pos >= end) return end
                val c = buffer[pos]
                when {
                    // Only its `}` ends a field: a quote in one opens a string of its own, and a
                    // line break in one is the expression's, in a single-quoted literal too.
                    c == '}' && depth == 0 -> {
                        emit(pos, pos + 1, BasedPythonTokenTypes.FSTRING_RBRACE)
                        return pos + 1
                    }
                    depth == 0 && c == '!' && (pos + 1 >= end || buffer[pos + 1] != '=') -> {
                        var name = pos + 1
                        while (name < end && (buffer[name].isLetterOrDigit() || buffer[name] == '_')) name++
                        emit(pos, name, BasedPythonTokenTypes.FSTRING_CONVERSION)
                        pos = name
                    }
                    depth == 0 && c == ':' -> {
                        emit(pos, pos + 1, BasedPythonTokenTypes.FSTRING_FORMAT_START)
                        pos = spec(pos + 1, quote, triple)
                    }
                    else -> {
                        val next = token(pos)
                        when (buffer[pos]) {
                            '(', '[', '{' -> depth++
                            ')', ']', '}' -> if (depth > 0) depth--
                        }
                        pos = next
                    }
                }
            }
        }

        /** A format spec after its `:`, up to the `}` that ends its field; returns where that `}` is. */
        private fun spec(from: Int, quote: Char, triple: Boolean): Int {
            var pos = from
            var text = from
            fun flush() {
                if (pos > text) emit(text, pos, BasedPythonTokenTypes.FSTRING_TEXT)
            }
            while (true) {
                if (pos >= end) return end.also { flush() }
                val c = buffer[pos]
                when {
                    c == '}' || closesAt(pos, quote, triple) || endsLine(pos, triple) -> return pos.also { flush() }
                    c == '{' -> {
                        flush()
                        pos = field(pos, quote, triple)
                        text = pos
                    }
                    else -> pos++
                }
            }
        }

        /** One token of a field's expression at [pos]; emits it and returns where it ends. */
        private fun token(pos: Int): Int {
            var quoteAt = pos
            while (quoteAt < end && quoteAt - pos < 2 && BasedPythonLexer.isStringPrefix(buffer[quoteAt])) quoteAt++
            val quoted = quoteAt < end && (buffer[quoteAt] == '"' || buffer[quoteAt] == '\'')
            if (quoted && isInterpolated(buffer, pos, quoteAt)) return literal(pos)
            tokens.start(buffer, pos, end, 0)
            val type = tokens.tokenType ?: return end
            val tokenEnd = maxOf(tokens.tokenEnd, pos + 1)
            emit(pos, tokenEnd, type)
            return tokenEnd
        }
    }
}
