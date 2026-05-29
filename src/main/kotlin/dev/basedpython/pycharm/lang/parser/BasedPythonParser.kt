package dev.basedpython.pycharm.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lang.psi.BasedPythonElementTypes

/**
 * Tolerant recursive parser over the INDENT/DEDENT/STATEMENT_BREAK stream produced by
 * [BasedPythonIndentingLexer]. Builds file → statements; functions/classes get a parameter
 * list and an optional BLOCK suite. The parser NEVER throws, always consumes to EOF, and
 * closes every marker. On any stuck marker it consumes a single token as an error and
 * continues, so it cannot infinite-loop.
 */
class BasedPythonParser : PsiParser {

    private val INDENT = BasedPythonTokenTypes.INDENT
    private val DEDENT = BasedPythonTokenTypes.DEDENT
    private val BREAK = BasedPythonTokenTypes.STATEMENT_BREAK
    private val KEYWORD = BasedPythonTokenTypes.KEYWORD
    private val IDENT = BasedPythonTokenTypes.IDENTIFIER
    private val LPAREN = BasedPythonTokenTypes.LPAREN
    private val RPAREN = BasedPythonTokenTypes.RPAREN
    private val COMMA = BasedPythonTokenTypes.COMMA
    private val OP = BasedPythonTokenTypes.OPERATOR

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val fileMarker = builder.mark()
        parseStatementList(builder, atTopLevel = true)
        // Defensive: drain anything left (shouldn't happen at top level).
        while (!builder.eof()) builder.advanceLexer()
        fileMarker.done(root)
        return builder.treeBuilt
    }

    /** Parses statements until EOF or an unmatched DEDENT (which the caller consumes). */
    private fun parseStatementList(builder: PsiBuilder, atTopLevel: Boolean) {
        while (!builder.eof()) {
            val t = builder.tokenType
            if (t === DEDENT) {
                if (atTopLevel) {
                    // Stray DEDENT at top level: consume as error, keep going.
                    val m = builder.mark()
                    builder.advanceLexer()
                    m.error("Unexpected dedent")
                    continue
                }
                return // let the block parser consume the matching DEDENT
            }
            if (t === BREAK || t === INDENT) {
                // Stray separator/indent: consume silently (tolerant).
                builder.advanceLexer()
                continue
            }
            val before = builder.currentOffset
            parseStatement(builder)
            // Guarantee forward progress.
            if (builder.currentOffset == before && !builder.eof()) {
                val m = builder.mark()
                builder.advanceLexer()
                m.error("Unexpected token")
            }
        }
    }

    private fun keywordText(builder: PsiBuilder): String? =
        if (builder.tokenType === KEYWORD) builder.tokenText else null

    private fun parseStatement(builder: PsiBuilder) {
        // Decorators: @expr ... STATEMENT_BREAK, attach before following def/class.
        if (builder.tokenType === OP && builder.tokenText == "@") {
            parseDecorated(builder)
            return
        }
        val kw = keywordText(builder)
        when {
            kw == "def" || kw == "async" -> parseFunction(builder, null)
            isClassStart(builder) -> parseClass(builder, null)
            kw == "import" || kw == "from" -> parseImport(builder)
            else -> parseGenericStatement(builder)
        }
    }

    /** Collects leading decorators then parses the following def/class (or a generic stmt). */
    private fun parseDecorated(builder: PsiBuilder) {
        // Parse one decorator line.
        val dm = builder.mark()
        while (!builder.eof() && builder.tokenType !== BREAK && builder.tokenType !== INDENT &&
            builder.tokenType !== DEDENT
        ) {
            builder.advanceLexer()
        }
        if (builder.tokenType === BREAK) builder.advanceLexer()
        dm.done(BasedPythonElementTypes.DECORATOR)

        // Follow with more decorators or the decorated target.
        if (builder.tokenType === OP && builder.tokenText == "@") {
            parseDecorated(builder)
            return
        }
        val kw = keywordText(builder)
        when {
            kw == "def" || kw == "async" -> parseFunction(builder, null)
            isClassStart(builder) -> parseClass(builder, null)
            builder.eof() || builder.tokenType === DEDENT -> { /* dangling decorator: tolerated */ }
            else -> parseGenericStatement(builder)
        }
    }

    private fun isClassStart(builder: PsiBuilder): Boolean {
        val kw = keywordText(builder) ?: return false
        return kw == "class" || kw == "protocol" || kw == "data" || kw == "frozen" || kw == "enum"
    }

    private fun parseFunction(builder: PsiBuilder, ignored: Any?) {
        val m = builder.mark()
        // async? def
        if (keywordText(builder) == "async") builder.advanceLexer()
        if (keywordText(builder) == "def") builder.advanceLexer()
        // name
        if (builder.tokenType === IDENT) builder.advanceLexer()
        // parameter list
        if (builder.tokenType === LPAREN) parseParameterList(builder)
        // -> return annotation and ':' up to STATEMENT_BREAK
        consumeUntilBreak(builder)
        if (builder.tokenType === BREAK) builder.advanceLexer()
        // optional suite
        parseOptionalSuite(builder)
        m.done(BasedPythonElementTypes.FUNCTION_DECLARATION)
    }

    private fun parseClass(builder: PsiBuilder, ignored: Any?) {
        val m = builder.mark()
        // consume class-kind keywords: (data|frozen|enum)* class | protocol
        var guard = 0
        while (!builder.eof() && builder.tokenType === KEYWORD && guard++ < 6) {
            val kw = builder.tokenText
            builder.advanceLexer()
            if (kw == "class" || kw == "protocol") break
        }
        // name
        if (builder.tokenType === IDENT) builder.advanceLexer()
        // optional bases (...) and ':' up to break
        consumeUntilBreak(builder)
        if (builder.tokenType === BREAK) builder.advanceLexer()
        parseOptionalSuite(builder)
        m.done(BasedPythonElementTypes.CLASS_DECLARATION)
    }

    private fun parseImport(builder: PsiBuilder) {
        val m = builder.mark()
        consumeUntilBreak(builder)
        if (builder.tokenType === BREAK) builder.advanceLexer()
        m.done(BasedPythonElementTypes.IMPORT_STATEMENT)
    }

    private fun parseGenericStatement(builder: PsiBuilder) {
        val m = builder.mark()
        consumeUntilBreak(builder)
        if (builder.tokenType === BREAK) builder.advanceLexer()
        // A compound header (if/for/while/etc.) may carry a suite; keep the tree balanced.
        parseOptionalSuite(builder)
        m.done(BasedPythonElementTypes.STATEMENT)
    }

    /** If the next token is INDENT, parse `INDENT statement* DEDENT` as a BLOCK. */
    private fun parseOptionalSuite(builder: PsiBuilder) {
        if (builder.tokenType !== INDENT) return
        val block = builder.mark()
        builder.advanceLexer() // INDENT
        parseStatementList(builder, atTopLevel = false)
        if (builder.tokenType === DEDENT) {
            builder.advanceLexer()
        }
        block.done(BasedPythonElementTypes.BLOCK)
    }

    private fun parseParameterList(builder: PsiBuilder) {
        val m = builder.mark()
        builder.advanceLexer() // '('
        // Split on top-level commas into PARAMETER nodes.
        var depth = 0
        var paramMarker: PsiBuilder.Marker? = null
        var sawParamContent = false

        fun closeParam() {
            paramMarker?.let {
                if (sawParamContent) it.done(BasedPythonElementTypes.PARAMETER) else it.drop()
            }
            paramMarker = null
            sawParamContent = false
        }

        while (!builder.eof()) {
            val t = builder.tokenType
            if (t === BREAK) { builder.advanceLexer(); continue } // tolerate stray breaks inside ()
            if (t === INDENT || t === DEDENT) { builder.advanceLexer(); continue }
            if (t === RPAREN && depth == 0) {
                closeParam()
                builder.advanceLexer()
                break
            }
            when (t) {
                LPAREN, BasedPythonTokenTypes.LBRACKET, BasedPythonTokenTypes.LBRACE -> depth++
                RPAREN, BasedPythonTokenTypes.RBRACKET, BasedPythonTokenTypes.RBRACE ->
                    if (depth > 0) depth--
            }
            if (t === COMMA && depth == 0) {
                closeParam()
                builder.advanceLexer()
                continue
            }
            // Start a new parameter on first meaningful token.
            if (paramMarker == null) paramMarker = builder.mark()
            sawParamContent = true
            builder.advanceLexer()
        }
        // If we exited via EOF without a closing paren, finish any open param.
        closeParam()
        m.done(BasedPythonElementTypes.PARAMETER_LIST)
    }

    /** Consume tokens until a STATEMENT_BREAK / INDENT / DEDENT / EOF (does not consume them). */
    private fun consumeUntilBreak(builder: PsiBuilder) {
        while (!builder.eof()) {
            val t = builder.tokenType
            if (t === BREAK || t === INDENT || t === DEDENT) return
            builder.advanceLexer()
        }
    }
}
