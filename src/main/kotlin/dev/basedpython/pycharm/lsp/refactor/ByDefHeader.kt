package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * A `def`'s header as the tokens after its name write it: its parameters, each the text the source
 * gives it, and its return annotation — read from the name on, so that it can be read wherever the
 * name is, without `by` and on the EDT.
 *
 * Purely lexical, and only what a suggested Change Signature needs to tell one edit of a header from
 * another; which token is a definition's name, and what the parameters mean, are `by`'s.
 */
internal data class ByDefHeader(
    val name: TextRange,
    val parameters: List<Parameter>,
    /** The return annotation as written, after `->`; null when there is none. */
    val returns: String?,
    /** From the name to the end of the return annotation, or of the parameter list without one. */
    val range: TextRange,
) {
    /**
     * One parameter: [name] its name, [stars] the `*` or `**` before it, [section] how many of the
     * list's `/` and bare `*` come before it, and [rest] what follows the name — its annotation and
     * default — as written; [after] is the `,` or `)` after it.
     */
    data class Parameter(
        val name: TextRange,
        val after: TextRange,
        val stars: String,
        val section: Int,
        val rest: String,
        val hasDefault: Boolean,
    )

    companion object {
        /** The header of the `def` whose name is [name] in [text]; null where it is not a whole one. */
        fun read(text: CharSequence, name: TextRange): ByDefHeader? {
            val tokens = Tokens(text, name.endOffset)
            if (tokens.type == BasedPythonTokenTypes.LBRACKET && !tokens.skipBalanced()) return null
            if (tokens.type != BasedPythonTokenTypes.LPAREN) return null
            val parameters = mutableListOf<Parameter>()
            var section = 0
            var segment = mutableListOf<Token>()
            tokens.advance()
            var depth = 0
            while (true) {
                val type = tokens.type ?: return null
                when {
                    depth == 0 && (type == BasedPythonTokenTypes.COMMA || type == BasedPythonTokenTypes.RPAREN) -> {
                        when (val read = parameter(text, segment, section, TextRange(tokens.start, tokens.end))) {
                            null -> if (segment.isNotEmpty()) section++
                            else -> parameters += read
                        }
                        segment = mutableListOf()
                        if (type == BasedPythonTokenTypes.RPAREN) break
                    }
                    else -> {
                        if (type in OPENING) depth++
                        if (type in CLOSING) depth--
                        segment += tokens.token(type)
                    }
                }
                tokens.advance()
            }
            var end = tokens.end
            tokens.advance()
            var returns: String? = null
            if (tokens.type == BasedPythonTokenTypes.OPERATOR && tokens.text == "->") {
                tokens.advance()
                val start = tokens.start
                if (tokens.type == BasedPythonTokenTypes.COLON) return null
                depth = 0
                while (true) {
                    val type = tokens.type ?: return null
                    if (depth == 0 && type == BasedPythonTokenTypes.COLON) break
                    if (type in OPENING) depth++
                    if (type in CLOSING) depth--
                    end = tokens.end
                    tokens.advance()
                }
                returns = text.subSequence(start, end).toString()
            } else if (tokens.type != BasedPythonTokenTypes.COLON) {
                return null
            }
            return ByDefHeader(name, parameters, returns, TextRange(name.startOffset, end))
        }

        /** One comma-separated part of the list; null for a `/` or bare `*`, which only separates. */
        private fun parameter(text: CharSequence, segment: List<Token>, section: Int, delimiter: TextRange): Parameter? {
            val first = segment.firstOrNull() ?: return null
            val stars = first.text.takeIf { first.type == BasedPythonTokenTypes.OPERATOR && (it == "*" || it == "**") }
            val index = if (stars == null) 0 else 1
            val name = segment.getOrNull(index)
                ?.takeIf { it.type == BasedPythonTokenTypes.IDENTIFIER || it.type == BasedPythonTokenTypes.KEYWORD }
                ?: return null
            val rest = text.subSequence(name.end, segment.last().end).toString()
            var depth = 0
            var hasDefault = false
            for (token in segment) {
                if (token.type in OPENING) depth++
                if (token.type in CLOSING) depth--
                if (depth == 0 && token.type == BasedPythonTokenTypes.OPERATOR && token.text == "=") hasDefault = true
            }
            return Parameter(TextRange(name.start, name.end), delimiter, stars.orEmpty(), section, rest, hasDefault)
        }

        private val OPENING = setOf(BasedPythonTokenTypes.LPAREN, BasedPythonTokenTypes.LBRACKET, BasedPythonTokenTypes.LBRACE)
        private val CLOSING = setOf(BasedPythonTokenTypes.RPAREN, BasedPythonTokenTypes.RBRACKET, BasedPythonTokenTypes.RBRACE)
    }

    private class Token(val type: IElementType, val start: Int, val end: Int, val text: String)

    /** The significant tokens of [source] from [from]: whitespace, line breaks and comments left out. */
    private class Tokens(private val source: CharSequence, from: Int) {
        private val lexer = BasedPythonLexer().apply { start(source, from, source.length, 0) }

        init {
            skipTrivia()
        }

        val type: IElementType? get() = lexer.tokenType
        val start: Int get() = lexer.tokenStart
        val end: Int get() = lexer.tokenEnd
        val text: String get() = source.subSequence(lexer.tokenStart, lexer.tokenEnd).toString()

        fun token(type: IElementType): Token = Token(type, start, end, text)

        fun advance() {
            lexer.advance()
            skipTrivia()
        }

        /** Past a bracketed group starting here, its closing bracket included; false where it does not close. */
        fun skipBalanced(): Boolean {
            var depth = 0
            while (true) {
                val type = type ?: return false
                if (type in OPENING) depth++
                if (type in CLOSING) depth--
                advance()
                if (depth == 0) return true
            }
        }

        private fun skipTrivia() {
            while (lexer.tokenType == BasedPythonTokenTypes.WHITESPACE || lexer.tokenType == BasedPythonTokenTypes.COMMENT) lexer.advance()
        }
    }
}
