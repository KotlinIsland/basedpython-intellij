package dev.basedpython.pycharm.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Semantic annotator for BasedPython (.by) files.
 *
 * Because the PSI tree is flat (all content is token leaves under the file root) we run
 * the annotator only on the top-level PsiFile element, walking the entire token stream once
 * per file pass. This avoids re-entering per child-leaf and keeps allocations low.
 *
 * Highlights applied (lexer-driven fallbacks — LSP semantic tokens take priority when server runs):
 *   - Python / BasedPython builtins → BUILTIN_NAME
 *   - `self` / `cls` (first-param or general reference) → SELF_PARAMETER
 *   - `@decorator` spans → DECORATOR
 *   - name immediately following `def` / `class` / `data class` / `protocol` → FUNCTION/CLASS_DECLARATION
 *   - names in parameter position (inside `def(…)` signature, excluding `self`/`cls`) → PARAMETER
 *   - PascalCase identifiers after `:` or `->` (type annotation positions) → TYPE_NAME
 *   - `name=` patterns inside call argument lists → KEYWORD_ARGUMENT
 *   - Escape sequences inside string/bytes tokens → STRING_ESCAPE (sub-ranges)
 *   - `{…}` interpolation spans inside f-string tokens → FSTRING_INTERP (sub-ranges)
 */
class BasedPythonAnnotator : Annotator {

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only run once per file, on the file root itself.
        val file = element as? com.intellij.psi.PsiFile ?: return
        val text = file.text ?: return
        if (text.isEmpty()) return

        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)

        // We need to collect tokens in order so we can look ahead/back cheaply.
        // Use a minimal two-pass approach: collect token list, then walk with index.
        // Cap at a generous token limit to avoid freezing on huge generated files.
        val tokens = ArrayList<TokEntry>(512)
        var t = lexer.tokenType
        while (t != null) {
            tokens.add(TokEntry(t, lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
            t = lexer.tokenType
        }

        walkTokens(tokens, text, holder, element.textRange.startOffset)
    }

    // -------------------------------------------------------------------------
    // Main walk
    // -------------------------------------------------------------------------

    private fun walkTokens(
        tokens: List<TokEntry>,
        text: String,
        holder: AnnotationHolder,
        baseOffset: Int
    ) {
        val n = tokens.size

        // Token views (type + text) for the context-free soft-keyword classifier.
        val tokViews = tokens.map { BasedPythonSoftKeywords.Tok(it.type, text.substring(it.start, it.end)) }

        // Small state machine tracking whether we are:
        //   - inside a def-parameter list (to highlight PARAMETER)
        //   - expecting a function/class name after def/class keyword
        var expectDefName = false         // next IDENTIFIER after `def`
        var expectClassName = false       // next IDENTIFIER after `class`/`protocol`/`data class`/`newtype`
        var inParamList = false           // inside `def foo(` … `):`
        var paramParenDepth = 0           // tracks nested parens inside param list
        var afterColon = false            // just saw `:` (type annotation)
        var afterArrow = false            // just saw `->` (return type)
        var inCallArgList = false         // inside a plain call `foo(`
        var callParenDepth = 0

        // We also need to detect `data` keyword followed by `class` —
        // track the previous non-ws keyword.
        var prevKeyword = ""
        var prevTokIdx = -1

        var i = 0
        while (i < n) {
            val tok = tokens[i]
            val tokText = text.substring(tok.start, tok.end)

            when (tok.type) {

                // ── KEYWORD tokens ──────────────────────────────────────────
                BasedPythonTokenTypes.KEYWORD -> {
                    // Soft keywords used outside a keyword position (e.g. `x = out`, `type(x)`,
                    // `obj.match`) are demoted to identifier colour, like a parser would.
                    if (BasedPythonSoftKeywords.isSoft(tokText) &&
                        !BasedPythonSoftKeywords.isKeyword(tokViews, i)
                    ) {
                        demoteKeyword(holder, baseOffset, tok.start, tok.end, tokText)
                        prevKeyword = ""
                        afterColon = false
                        afterArrow = false
                        i++; continue
                    }
                    when (tokText) {
                        "def" -> {
                            expectDefName = true
                            expectClassName = false
                            inParamList = false
                            afterColon = false
                            afterArrow = false
                        }
                        "class" -> {
                            // `data class` or plain `class`
                            expectClassName = true
                            expectDefName = false
                        }
                        "protocol", "newtype" -> {
                            expectClassName = true
                            expectDefName = false
                        }
                        else -> {
                            // Don't reset annotation state for other keywords like `async`.
                        }
                    }
                    prevKeyword = tokText
                    prevTokIdx = i
                    afterColon = false
                    afterArrow = false
                    i++; continue
                }

                // ── IDENTIFIER tokens ────────────────────────────────────────
                BasedPythonTokenTypes.IDENTIFIER -> {
                    when {
                        expectDefName -> {
                            // The identifier right after `def` is the function name.
                            highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.FUNCTION_DECLARATION)
                            expectDefName = false
                            // After function name, we expect `(` for the param list.
                            // Look ahead for the `(`.
                            val nextMeaningful = nextNonWs(tokens, i + 1)
                            if (nextMeaningful != null && nextMeaningful.type == BasedPythonTokenTypes.LPAREN) {
                                inParamList = true
                                paramParenDepth = 0
                            }
                        }

                        expectClassName -> {
                            highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.CLASS_DECLARATION)
                            expectClassName = false
                        }

                        inParamList && paramParenDepth == 0 -> {
                            // Inside the outermost level of a def-param list.
                            when (tokText) {
                                "self", "cls" -> highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.SELF_PARAMETER)
                                else -> {
                                    // Could be `name:`, `name=`, `*name`, `**name`
                                    // Check if this is followed by `:` or `=` (param) or `,` / `)`.
                                    val nx = nextNonWs(tokens, i + 1)
                                    if (nx != null && (nx.type == BasedPythonTokenTypes.COLON ||
                                                nx.type == BasedPythonTokenTypes.OPERATOR && text.substring(nx.start, nx.end) == "==" ||
                                                nx.type == BasedPythonTokenTypes.COMMA ||
                                                nx.type == BasedPythonTokenTypes.RPAREN)) {
                                        highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.PARAMETER)
                                    } else if (nx != null && nx.type == BasedPythonTokenTypes.OPERATOR &&
                                               text.substring(nx.start, nx.end) == "=") {
                                        // default value param
                                        highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.PARAMETER)
                                    } else {
                                        highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.PARAMETER)
                                    }
                                }
                            }
                        }

                        // self/cls anywhere (method body refs)
                        tokText == "self" || tokText == "cls" -> {
                            highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.SELF_PARAMETER)
                        }

                        // After `:` or `->` → type annotation position
                        (afterColon || afterArrow) -> {
                            if (isPascalCase(tokText) || isBuiltin(tokText) && tokText[0].isUpperCase()) {
                                highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.TYPE_NAME)
                            } else if (isBuiltin(tokText)) {
                                highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.BUILTIN_NAME)
                            }
                            // Keep afterColon/afterArrow alive in case of compound types like `list[int]`
                            // but we'll reset on next operator/keyword below.
                        }

                        // Keyword argument: identifier immediately followed by `=` not `==`
                        inCallArgList && callParenDepth == 0 -> {
                            val nx = nextNonWs(tokens, i + 1)
                            if (nx != null && nx.type == BasedPythonTokenTypes.OPERATOR) {
                                val nxText = text.substring(nx.start, nx.end)
                                if (nxText == "=") {
                                    highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.KEYWORD_ARGUMENT)
                                    i++; continue
                                }
                            }
                            if (isBuiltin(tokText)) {
                                highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.BUILTIN_NAME)
                            }
                        }

                        // Builtin names
                        isBuiltin(tokText) -> {
                            highlight(holder, baseOffset, tok.start, tok.end, BasedPythonHighlightKeys.BUILTIN_NAME)
                        }
                    }
                    afterColon = false
                    afterArrow = false
                    i++; continue
                }

                // ── OPERATOR tokens (includes `->`, `=`, `==`) ───────────────
                BasedPythonTokenTypes.OPERATOR -> {
                    val opText = text.substring(tok.start, tok.end)
                    afterArrow = (opText == "->")
                    if (opText != "->") afterColon = false
                    // Don't reset inParamList on operators.
                }

                // ── COLON ────────────────────────────────────────────────────
                BasedPythonTokenTypes.COLON -> {
                    afterColon = true
                    afterArrow = false
                }

                // ── LPAREN ───────────────────────────────────────────────────
                BasedPythonTokenTypes.LPAREN -> {
                    if (inParamList) {
                        paramParenDepth++
                        // The `(` that opens the param list itself is already consumed
                        // by the expectDefName branch above; the first LPAREN we see here
                        // means we are entering a nested expression.
                    } else {
                        // Check if we are entering a call-arg list.
                        // The token before this `(` (non-ws) should be an IDENTIFIER or RPAREN/RBRACKET.
                        val prev = prevNonWs(tokens, i - 1)
                        if (prev != null && (prev.type == BasedPythonTokenTypes.IDENTIFIER ||
                                    prev.type == BasedPythonTokenTypes.RPAREN ||
                                    prev.type == BasedPythonTokenTypes.RBRACKET)) {
                            if (!inCallArgList) {
                                inCallArgList = true
                                callParenDepth = 0
                            } else {
                                callParenDepth++
                            }
                        } else if (inCallArgList) {
                            callParenDepth++
                        }
                    }
                    afterColon = false
                    afterArrow = false
                }

                // ── RPAREN ───────────────────────────────────────────────────
                BasedPythonTokenTypes.RPAREN -> {
                    when {
                        inParamList && paramParenDepth > 0 -> paramParenDepth--
                        inParamList && paramParenDepth == 0 -> {
                            inParamList = false
                        }
                        inCallArgList && callParenDepth > 0 -> callParenDepth--
                        inCallArgList && callParenDepth == 0 -> {
                            inCallArgList = false
                        }
                    }
                    afterColon = false
                    afterArrow = false
                }

                // ── STRING tokens ────────────────────────────────────────────
                BasedPythonTokenTypes.STRING -> {
                    highlightString(tok, text, baseOffset, holder)
                    afterColon = false
                    afterArrow = false
                    i++; continue
                }

                else -> {
                    if (tok.type != BasedPythonTokenTypes.WHITESPACE) {
                        afterColon = false
                        afterArrow = false
                    }
                }
            }

            // ── Decorator detection ─────────────────────────────────────────
            // `@` is emitted as an OPERATOR. After `@`, the following identifier
            // (and any dotted continuation) should be DECORATOR.
            // We handle this separately with a look-behind on the operator.
            // Handled inside the OPERATOR block above via a state flag.

            i++
        }

        // Second pass: decorator highlighting.
        // `@` starts a decorator line. We scan for OPERATOR `@` and highlight
        // from `@` through the end of the dotted name that follows.
        for (j in tokens.indices) {
            val tok = tokens[j]
            if (tok.type == BasedPythonTokenTypes.OPERATOR && text.substring(tok.start, tok.end) == "@") {
                // Highlight from `@` through any `identifier.identifier` chain.
                var end = tok.end
                var k = j + 1
                while (k < tokens.size) {
                    val nt = tokens[k]
                    if (nt.type == BasedPythonTokenTypes.WHITESPACE) { k++; continue }
                    if (nt.type == BasedPythonTokenTypes.IDENTIFIER ||
                        nt.type == BasedPythonTokenTypes.DOT) {
                        end = nt.end
                        k++
                    } else {
                        break
                    }
                }
                if (end > tok.start) {
                    highlight(holder, baseOffset, tok.start, end, BasedPythonHighlightKeys.DECORATOR)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // String sub-highlighting: escapes and f-string interpolations
    // -------------------------------------------------------------------------

    private fun highlightString(tok: TokEntry, text: String, baseOffset: Int, holder: AnnotationHolder) {
        val raw = text.substring(tok.start, tok.end)
        val rawLen = raw.length

        // Determine prefix and quote character.
        var prefixEnd = 0
        var isFString = false
        var isRaw = false
        while (prefixEnd < rawLen && raw[prefixEnd].lowercaseChar().let { it == 'r' || it == 'b' || it == 'f' || it == 'u' }) {
            val ch = raw[prefixEnd].lowercaseChar()
            if (ch == 'f') isFString = true
            if (ch == 'r') isRaw = true
            prefixEnd++
        }

        if (prefixEnd >= rawLen) return  // malformed
        val q = raw[prefixEnd]
        if (q != '"' && q != '\'') return

        val triple = prefixEnd + 2 < rawLen && raw[prefixEnd + 1] == q && raw[prefixEnd + 2] == q
        val contentStart = prefixEnd + (if (triple) 3 else 1)
        val contentEnd = if (triple) {
            if (rawLen >= 6 && raw.endsWith("$q$q$q")) rawLen - 3 else rawLen
        } else {
            if (rawLen > contentStart && raw[rawLen - 1] == q) rawLen - 1 else rawLen
        }

        if (contentStart >= contentEnd) return

        var i = contentStart
        var fDepth = 0  // brace depth inside f-string interpolation
        var fInterpStart = -1

        while (i < contentEnd) {
            val c = raw[i]

            // F-string interpolation tracking
            if (isFString) {
                when {
                    c == '{' && i + 1 < contentEnd && raw[i + 1] == '{' -> {
                        // escaped `{{` — not interpolation
                        i += 2; continue
                    }
                    c == '{' -> {
                        fDepth++
                        if (fDepth == 1) fInterpStart = i
                        i++; continue
                    }
                    c == '}' && i + 1 < contentEnd && raw[i + 1] == '}' -> {
                        // escaped `}}`
                        i += 2; continue
                    }
                    c == '}' && fDepth > 0 -> {
                        fDepth--
                        if (fDepth == 0 && fInterpStart >= 0) {
                            val abs = tok.start + fInterpStart
                            highlight(holder, baseOffset, abs, tok.start + i + 1, BasedPythonHighlightKeys.FSTRING_INTERP)
                            fInterpStart = -1
                        }
                        i++; continue
                    }
                }
                // If inside interpolation, don't highlight escape sequences.
                if (fDepth > 0) { i++; continue }
            }

            // Escape sequences (only when not raw string)
            if (!isRaw && c == '\\' && i + 1 < contentEnd) {
                val next = raw[i + 1]
                val escLen = when (next) {
                    'n', 't', 'r', '\\', '\'', '"', 'a', 'b', 'f', 'v', '0', '\n' -> 2
                    'x' -> if (i + 3 < contentEnd && isHex(raw[i + 2]) && isHex(raw[i + 3])) 4 else 2
                    'u' -> if (i + 5 < contentEnd &&
                                isHex(raw[i + 2]) && isHex(raw[i + 3]) &&
                                isHex(raw[i + 4]) && isHex(raw[i + 5])) 6 else 2
                    'U' -> if (i + 9 < contentEnd &&
                                isHex(raw[i + 2]) && isHex(raw[i + 3]) &&
                                isHex(raw[i + 4]) && isHex(raw[i + 5]) &&
                                isHex(raw[i + 6]) && isHex(raw[i + 7]) &&
                                isHex(raw[i + 8]) && isHex(raw[i + 9])) 10 else 2
                    'N' -> {
                        // \N{name}
                        var k = i + 2
                        if (k < contentEnd && raw[k] == '{') {
                            k++
                            while (k < contentEnd && raw[k] != '}') k++
                            if (k < contentEnd) k - i + 1 else 2
                        } else 2
                    }
                    in '0'..'7' -> {
                        // up to 3 octal digits
                        var k = i + 2
                        var cnt = 1
                        while (k < contentEnd && cnt < 3 && raw[k] in '0'..'7') { k++; cnt++ }
                        k - i
                    }
                    else -> 2  // unknown escape — highlight anyway so user sees it
                }
                val abs = tok.start + i
                highlight(holder, baseOffset, abs, abs + escLen, BasedPythonHighlightKeys.STRING_ESCAPE)
                i += escLen
                continue
            }

            i++
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun highlight(
        holder: AnnotationHolder,
        baseOffset: Int,
        start: Int,
        end: Int,
        key: TextAttributesKey
    ) {
        if (end <= start) return
        val range = TextRange(baseOffset + start, baseOffset + end)
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(key)
            .create()
    }

    /**
     * Render a soft keyword that is being used as a plain identifier. We must *override* the
     * lexer's keyword colour, so for the plain case we enforce the editor's default text
     * attributes (a bare IDENTIFIER key could merge and leave the keyword colour showing).
     * When the word is also a builtin (`open`, `type`), colour it as a builtin instead.
     */
    private fun demoteKeyword(holder: AnnotationHolder, baseOffset: Int, start: Int, end: Int, tokText: String) {
        if (end <= start) return
        if (isBuiltin(tokText)) {
            highlight(holder, baseOffset, start, end, BasedPythonHighlightKeys.BUILTIN_NAME)
            return
        }
        val range = TextRange(baseOffset + start, baseOffset + end)
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .enforcedTextAttributes(defaultTextAttributes())
            .create()
    }

    private fun defaultTextAttributes(): TextAttributes =
        EditorColorsManager.getInstance().globalScheme.getAttributes(HighlighterColors.TEXT) ?: TextAttributes()

    private fun nextNonWs(tokens: List<TokEntry>, from: Int): TokEntry? {
        var k = from
        while (k < tokens.size) {
            if (tokens[k].type != BasedPythonTokenTypes.WHITESPACE) return tokens[k]
            k++
        }
        return null
    }

    private fun prevNonWs(tokens: List<TokEntry>, from: Int): TokEntry? {
        var k = from
        while (k >= 0) {
            if (tokens[k].type != BasedPythonTokenTypes.WHITESPACE) return tokens[k]
            k--
        }
        return null
    }

    private fun isPascalCase(s: String): Boolean {
        if (s.isEmpty() || !s[0].isUpperCase()) return false
        return s.any { it.isLetter() }
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // -------------------------------------------------------------------------
    // Token entry
    // -------------------------------------------------------------------------

    private data class TokEntry(
        val type: com.intellij.psi.tree.IElementType,
        val start: Int,
        val end: Int
    )

    // -------------------------------------------------------------------------
    // Builtin set
    // -------------------------------------------------------------------------

    companion object {
        private val BUILTINS: Set<String> = setOf(
            // Functions
            "abs", "aiter", "all", "anext", "any", "ascii",
            "bin", "bool", "breakpoint", "bytearray", "bytes",
            "callable", "chr", "classmethod", "compile", "complex",
            "delattr", "dict", "dir", "divmod",
            "enumerate", "eval", "exec",
            "filter", "float", "format", "frozenset",
            "getattr", "globals",
            "hasattr", "hash", "help", "hex",
            "id", "input", "int", "isinstance", "issubclass", "iter",
            "len", "list", "locals",
            "map", "max", "memoryview", "min",
            "next",
            "object", "oct", "open", "ord",
            "pow", "print", "property",
            "range", "repr", "reversed", "round",
            "set", "setattr", "slice", "sorted", "staticmethod", "str", "sum", "super",
            "tuple", "type",
            "vars",
            "zip",
            "__import__",
            // Exceptions
            "BaseException", "BaseExceptionGroup", "Exception", "ExceptionGroup",
            "ArithmeticError", "BufferError", "LookupError",
            "AssertionError", "AttributeError", "BlockingIOError", "BrokenPipeError",
            "ChildProcessError", "ConnectionAbortedError", "ConnectionError",
            "ConnectionRefusedError", "ConnectionResetError",
            "EOFError", "EnvironmentError", "FileExistsError", "FileNotFoundError",
            "FloatingPointError", "GeneratorExit",
            "IOError", "ImportError", "ImportWarning", "IndentationError",
            "IndexError", "InterruptedError", "IsADirectoryError",
            "KeyError", "KeyboardInterrupt",
            "MemoryError", "ModuleNotFoundError",
            "NameError", "NotADirectoryError", "NotImplementedError",
            "OSError", "OverflowError",
            "PermissionError", "ProcessLookupError",
            "RecursionError", "ReferenceError", "RuntimeError",
            "StopAsyncIteration", "StopIteration", "SyntaxError",
            "SystemError", "SystemExit",
            "TabError", "TimeoutError", "TypeError",
            "UnboundLocalError", "UnicodeDecodeError", "UnicodeEncodeError",
            "UnicodeError", "UnicodeTranslateError", "UserWarning",
            "ValueError",
            "Warning",
            "ZeroDivisionError",
            // Constants
            "NotImplemented", "Ellipsis", "__debug__",
            "__name__", "__doc__", "__package__", "__spec__", "__loader__",
            "__builtins__",
        )

        fun isBuiltin(name: String): Boolean = name in BUILTINS
    }
}
