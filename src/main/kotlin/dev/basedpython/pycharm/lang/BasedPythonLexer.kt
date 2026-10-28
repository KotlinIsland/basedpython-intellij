package dev.basedpython.pycharm.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Hand-written DFA lexer for basedpython.
 *
 * Recognizes a Python 3.10+ superset: identifiers, numeric literals (int/float/hex/oct/bin
 * with underscores, exponents), single- and triple-quoted strings (including raw/byte/f/t
 * prefixes, an f- or t-string read to its end as [FStrings] reads it), `#` line comments, and
 * operators including basedpython extras `?.` and `??`.
 * Keywords are reclassified after identifier scan.
 *
 * No semantic logic — just token boundaries. Heavy lifting (parsing, type info) lives in the LSP,
 * which is always preferred for anything it can answer.
 *
 * Unlike the annotator, this layer is *not* redundant with the LSP and cannot be dropped. The
 * platform requires a non-null lexer from `ParserDefinition.createLexer()` for `.by` to be a
 * language at all, and the brace matcher, quote handler, commenter, indent options, TODO scan and
 * spellchecker all read these tokens. `by`'s semantic tokens are AST-driven and only classify
 * identifiers, so strings, comments, numbers, operators and brackets are coloured from here in
 * every mode, LSP or not.
 *
 * The keyword set is the one part that overlaps the LSP, and it is the low-priority part: a keyword
 * the server reports as a `keyword` semantic token colours correctly whether or not it appears
 * below. Adding new basedpython keywords here is a convenience for the no-LSP case, not a
 * requirement — so a stale list is tolerable and not worth chasing.
 *
 * `out` is the one word read by where it stands, since it is as often a variable as a keyword: it is
 * the variance keyword of `[out T]`, `[in out T]` and `list[out T]` only where `by`'s parser reads
 * it as one, at the start of a type parameter or a subscript element (after `[` or `,`) with a name
 * after it, or after the variance `in` that starts one. The state of a token is what it needs to be
 * read again from its start: the significant token before it, while that is `[`, `,` or a variance
 * `in`, and [AFTER_OUT] for the tokens read to decide an `out` before them, so that the editor
 * re-reads the `out` when any of them changes.
 */
class BasedPythonLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var startOffset: Int = 0
    private var endOffset: Int = 0
    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var currentToken: IElementType? = null

    /** The state the current token starts in. */
    private var state: Int = NONE

    /** What the significant tokens read so far leave the next token in: [NONE] or one of the `AFTER_*`. */
    private var context: Int = NONE

    /** A token starting before this offset was read to decide an `out` before it. */
    private var lookaheadEnd: Int = -1

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        this.context = if (initialState == AFTER_OUT) NONE else initialState
        this.lookaheadEnd = -1
        advance()
    }

    override fun getState(): Int = state
    override fun getTokenType(): IElementType? = currentToken
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= endOffset) {
            currentToken = null
            return
        }
        state = if (tokenStart < lookaheadEnd) AFTER_OUT else context
        val c = buffer[tokenStart]
        val token = when {
            c == '\n' || c == '\r' -> scanNewline()
            isWhitespace(c) -> scanWhitespace()
            c == '#' -> scanComment()
            c == '"' || c == '\'' -> scanString(tokenStart)
            isStringPrefix(c) && isQuoteAfterPrefix(tokenStart) -> scanStringWithPrefix()
            isDigit(c) -> scanNumber()
            c == '.' && tokenStart + 1 < endOffset && isDigit(buffer[tokenStart + 1]) -> scanNumber()
            isIdentStart(c) -> scanIdentifierOrKeyword()
            else -> scanOperatorOrPunct()
        }
        currentToken = token
        context = when {
            token == BasedPythonTokenTypes.WHITESPACE || token == BasedPythonTokenTypes.COMMENT -> context
            token == BasedPythonTokenTypes.LBRACKET -> AFTER_BRACKET
            token == BasedPythonTokenTypes.COMMA -> AFTER_COMMA
            token == BasedPythonTokenTypes.KEYWORD && (context == AFTER_BRACKET || context == AFTER_COMMA) &&
                tokenEnd - tokenStart == 2 && buffer[tokenStart] == 'i' && buffer[tokenStart + 1] == 'n' -> AFTER_VARIANCE_IN
            else -> NONE
        }
    }

    // --- scanners ---

    private fun scanNewline(): IElementType {
        var i = tokenStart
        while (i < endOffset && (buffer[i] == '\n' || buffer[i] == '\r')) i++
        tokenEnd = i
        return BasedPythonTokenTypes.WHITESPACE
    }

    private fun scanWhitespace(): IElementType {
        var i = tokenStart
        while (i < endOffset && isWhitespace(buffer[i])) i++
        tokenEnd = i
        return BasedPythonTokenTypes.WHITESPACE
    }

    private fun scanComment(): IElementType {
        var i = tokenStart
        while (i < endOffset && buffer[i] != '\n' && buffer[i] != '\r') i++
        tokenEnd = i
        return BasedPythonTokenTypes.COMMENT
    }

    private fun isStringPrefix(c: Char): Boolean = Companion.isStringPrefix(c)

    /** Look ahead past up to two prefix chars to see if a quote follows. */
    private fun isQuoteAfterPrefix(start: Int): Boolean {
        var i = start
        var prefixLen = 0
        while (i < endOffset && prefixLen < 2 && isStringPrefix(buffer[i])) {
            i++; prefixLen++
        }
        if (prefixLen == 0) return false
        return i < endOffset && (buffer[i] == '"' || buffer[i] == '\'')
    }

    private fun scanStringWithPrefix(): IElementType {
        var i = tokenStart
        while (i < endOffset && isStringPrefix(buffer[i])) i++
        return scanString(i)
    }

    private fun scanString(quoteStart: Int): IElementType {
        if (FStrings.isInterpolated(buffer, tokenStart, quoteStart)) {
            // an f-string or t-string ends where its fields let it, which a quote inside one does not
            tokenEnd = FStrings.scan(buffer, tokenStart, endOffset)
            return BasedPythonTokenTypes.STRING
        }
        val q = buffer[quoteStart]
        // triple?
        val triple = quoteStart + 2 < endOffset && buffer[quoteStart + 1] == q && buffer[quoteStart + 2] == q
        var i = quoteStart + if (triple) 3 else 1
        if (triple) {
            while (i < endOffset) {
                val ch = buffer[i]
                if (ch == '\\' && i + 1 < endOffset) { i += 2; continue }
                if (ch == q && i + 2 < endOffset && buffer[i + 1] == q && buffer[i + 2] == q) {
                    i += 3; break
                }
                // also handle EOF-just-before-close
                if (ch == q && i + 2 == endOffset && i + 1 < endOffset && buffer[i + 1] == q) {
                    i = endOffset; break
                }
                if (ch == q && i + 1 == endOffset) { i = endOffset; break }
                i++
            }
        } else {
            while (i < endOffset) {
                val ch = buffer[i]
                if (ch == '\n' || ch == '\r') break
                if (ch == '\\' && i + 1 < endOffset) { i += 2; continue }
                if (ch == q) { i++; break }
                i++
            }
        }
        tokenEnd = i
        return BasedPythonTokenTypes.STRING
    }

    private fun scanNumber(): IElementType {
        var i = tokenStart
        // hex/oct/bin
        if (buffer[i] == '0' && i + 1 < endOffset) {
            val n = buffer[i + 1]
            if (n == 'x' || n == 'X') {
                i += 2
                while (i < endOffset && (isHexDigit(buffer[i]) || buffer[i] == '_')) i++
                tokenEnd = consumeIntSuffix(i); return BasedPythonTokenTypes.NUMBER
            }
            if (n == 'o' || n == 'O') {
                i += 2
                while (i < endOffset && ((buffer[i] in '0'..'7') || buffer[i] == '_')) i++
                tokenEnd = consumeIntSuffix(i); return BasedPythonTokenTypes.NUMBER
            }
            if (n == 'b' || n == 'B') {
                i += 2
                while (i < endOffset && (buffer[i] == '0' || buffer[i] == '1' || buffer[i] == '_')) i++
                tokenEnd = consumeIntSuffix(i); return BasedPythonTokenTypes.NUMBER
            }
        }
        // integer part
        while (i < endOffset && (isDigit(buffer[i]) || buffer[i] == '_')) i++
        // fractional
        if (i < endOffset && buffer[i] == '.' && (i + 1 >= endOffset || !isIdentStart(buffer[i + 1]))) {
            i++
            while (i < endOffset && (isDigit(buffer[i]) || buffer[i] == '_')) i++
        }
        // exponent
        if (i < endOffset && (buffer[i] == 'e' || buffer[i] == 'E')) {
            i++
            if (i < endOffset && (buffer[i] == '+' || buffer[i] == '-')) i++
            while (i < endOffset && (isDigit(buffer[i]) || buffer[i] == '_')) i++
        }
        // complex / long suffix
        if (i < endOffset && (buffer[i] == 'j' || buffer[i] == 'J')) i++
        tokenEnd = i
        return BasedPythonTokenTypes.NUMBER
    }

    private fun consumeIntSuffix(i0: Int): Int {
        var i = i0
        if (i < endOffset && (buffer[i] == 'j' || buffer[i] == 'J' || buffer[i] == 'l' || buffer[i] == 'L')) i++
        return i
    }

    private fun scanIdentifierOrKeyword(): IElementType {
        var i = tokenStart
        while (i < endOffset && isIdentPart(buffer[i])) i++
        tokenEnd = i
        val text = buffer.subSequence(tokenStart, i).toString()
        if (text == "out") return if (isVarianceOut()) BasedPythonTokenTypes.KEYWORD else BasedPythonTokenTypes.IDENTIFIER
        return if (text in BasedPythonTokenTypes.ALL_KEYWORDS) BasedPythonTokenTypes.KEYWORD
        else BasedPythonTokenTypes.IDENTIFIER
    }

    /**
     * Whether the `out` just scanned is a variance keyword, as `by`'s parser decides it: always right
     * after a variance `in`, and at the start of an element when a name follows it, since two names
     * in a row are never an expression, while `[out]`, `[out[0]]` and `[out cast int]` read a
     * variable. After `[` the element is inside brackets, so its name may be on a later line; after
     * `,` it may be a top-level tuple's, `a, out` ending a statement, so the name must be on the same
     * line.
     */
    private fun isVarianceOut(): Boolean {
        val acrossLines = when (context) {
            AFTER_VARIANCE_IN -> return true
            AFTER_BRACKET -> true
            AFTER_COMMA -> false
            else -> return false
        }
        val next = skipTrivia(tokenEnd, acrossLines)
        lookaheadEnd = next + 1
        val word = wordAt(next) ?: return false
        if (word in HARD_KEYWORDS) return false
        if (word != "cast") return true
        // `out cast int` casts a variable named out; `cast` is a name only where no operand follows
        val operand = skipTrivia(next + word.length, acrossLines)
        lookaheadEnd = operand + 1
        return !startsCastOperand(operand)
    }

    /** Where the next token after [from] starts, past spaces, continuations and, [acrossLines], line breaks and comments. */
    private fun skipTrivia(from: Int, acrossLines: Boolean): Int {
        var i = from
        while (i < endOffset) {
            val ch = buffer[i]
            i = when {
                isWhitespace(ch) -> i + 1
                ch == '\\' && i + 1 < endOffset && (buffer[i + 1] == '\n' || buffer[i + 1] == '\r') -> i + 2
                acrossLines && (ch == '\n' || ch == '\r') -> i + 1
                acrossLines && ch == '#' -> {
                    var j = i
                    while (j < endOffset && buffer[j] != '\n' && buffer[j] != '\r') j++
                    j
                }
                else -> return i
            }
        }
        return i
    }

    private fun wordAt(at: Int): String? {
        if (at >= endOffset || !isIdentStart(buffer[at])) return null
        var i = at
        while (i < endOffset && isIdentPart(buffer[i])) i++
        return buffer.subSequence(at, i).toString()
    }

    /** Whether what starts at [at] makes a `cast` before it an infix cast: `!`, `?` or the start of an expression. */
    private fun startsCastOperand(at: Int): Boolean {
        if (at >= endOffset) return false
        val ch = buffer[at]
        val next = if (at + 1 < endOffset) buffer[at + 1] else ' '
        val word = wordAt(at)
        return when {
            word != null -> word !in HARD_KEYWORDS || word in EXPRESSION_KEYWORDS
            isDigit(ch) || ch == '"' || ch == '\'' -> true
            ch == '.' -> isDigit(next) || next == '.' && at + 2 < endOffset && buffer[at + 2] == '.'
            ch == '!' -> next != '='
            ch == '?' -> next != '.' && next != '?'
            ch == '(' || ch == '[' || ch == '{' || ch == '~' -> true
            ch == '-' -> next != '=' && next != '>'
            ch == '+' -> next != '='
            ch == '*' -> next != '=' && !(next == '*' && at + 2 < endOffset && buffer[at + 2] == '=')
            else -> false
        }
    }

    private fun scanOperatorOrPunct(): IElementType {
        val c = buffer[tokenStart]
        val c1 = if (tokenStart + 1 < endOffset) buffer[tokenStart + 1] else ' '
        val c2 = if (tokenStart + 2 < endOffset) buffer[tokenStart + 2] else ' '

        when (c) {
            '(' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.LPAREN }
            ')' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.RPAREN }
            '[' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.LBRACKET }
            ']' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.RBRACKET }
            '{' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.LBRACE }
            '}' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.RBRACE }
            ',' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.COMMA }
            ';' -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.SEMICOLON }
            ':' -> {
                tokenEnd = if (c1 == '=') tokenStart + 2 else tokenStart + 1
                return if (c1 == '=') BasedPythonTokenTypes.OPERATOR else BasedPythonTokenTypes.COLON
            }
            '.' -> {
                if (c1 == '.' && c2 == '.') { tokenEnd = tokenStart + 3; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.DOT
            }
            '?' -> {
                // basedpython: ?. (optional chaining), ?? (None-coalesce)
                if (c1 == '.' || c1 == '?') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.OPERATOR
            }
            '-' -> {
                if (c1 == '>') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                if (c1 == '=') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.OPERATOR
            }
            '*' -> {
                if (c1 == '*') { tokenEnd = if (c2 == '=') tokenStart + 3 else tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                if (c1 == '=') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.OPERATOR
            }
            '/' -> {
                if (c1 == '/') { tokenEnd = if (c2 == '=') tokenStart + 3 else tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                if (c1 == '=') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.OPERATOR
            }
            '<', '>' -> {
                if (c1 == c) { tokenEnd = if (c2 == '=') tokenStart + 3 else tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                if (c1 == '=') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.OPERATOR }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.OPERATOR
            }
            '=', '!', '+', '%', '&', '|', '^' -> {
                tokenEnd = if (c1 == '=') tokenStart + 2 else tokenStart + 1
                return BasedPythonTokenTypes.OPERATOR
            }
            '~', '@' -> {
                tokenEnd = if (c1 == '=') tokenStart + 2 else tokenStart + 1
                return BasedPythonTokenTypes.OPERATOR
            }
            '\\' -> {
                // line continuation — emit as whitespace if followed by newline, else bad char
                if (c1 == '\n' || c1 == '\r') { tokenEnd = tokenStart + 2; return BasedPythonTokenTypes.WHITESPACE }
                tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.BAD_CHARACTER
            }
            else -> { tokenEnd = tokenStart + 1; return BasedPythonTokenTypes.BAD_CHARACTER }
        }
    }

    // --- character classes ---

    private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == ''
    private fun isDigit(c: Char): Boolean = c in '0'..'9'
    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    private fun isIdentStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c.code > 127
    private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)

    companion object {
        /** The state of a token no `out`-deciding context comes before. */
        const val NONE = 0
        private const val AFTER_BRACKET = 1
        private const val AFTER_COMMA = 2
        private const val AFTER_VARIANCE_IN = 3
        /** The state of a token read to decide an `out` before it: one the editor never restarts at. */
        private const val AFTER_OUT = 4

        /** One past the largest state this lexer reports, for a lexer around it to number its own above. */
        const val STATE_COUNT = 5

        /** `by`'s keywords that are never a name; `match`, `case`, `type`, `cast` and `lazy` are soft. */
        private val HARD_KEYWORDS: Set<String> = BasedPythonTokenTypes.PYTHON_KEYWORDS - setOf("match", "case", "type")

        /** The hard keywords an expression can start with. */
        private val EXPRESSION_KEYWORDS: Set<String> = setOf("lambda", "await", "not", "yield", "None", "True", "False")

        /** A string prefix letter: raw, bytes, unicode, f-string, t-string. */
        fun isStringPrefix(c: Char): Boolean =
            c == 'r' || c == 'R' || c == 'b' || c == 'B' || c == 'f' || c == 'F' || c == 'u' || c == 'U' || c == 't' || c == 'T'
    }
}
