package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lang.FStrings

/**
 * Completion inside an f-string's or t-string's replacement field, `f"{car‸}"`, where `by` answers
 * `cart` as it does anywhere else and the IDE used to show nothing.
 *
 * A `.by` file's PSI keeps a whole string literal as one token — only the editor's highlighting
 * lexer splits an f-string into its parts — and `by` sends no edit range with its items, so the
 * platform filters them by the prefix it computes itself, which for a string token is the token up
 * to the caret, `f"{car`: no item starts with it, and every one was dropped. [ByCompletionSupport]
 * gives the identifier before the caret instead, inside a field's expression. (The popup itself is
 * not held back in a string: no completion confidence for basedpython skips it there.)
 *
 * Where a field's expression is, is the same lexical reading of the literal the highlighting lexer
 * draws its parts with ([FStrings]); everywhere else the platform's answer stands.
 */
internal object ByFStringFields {

    /**
     * Whether [offset] is in the expression of a replacement field of the interpolated string
     * literal [literal] of [text] — after its `{` and before the `}`, `!` or `:` that ends the
     * expression, or before the end of a literal still being typed.
     */
    fun inFieldCode(text: CharSequence, literal: TextRange, offset: Int): Boolean {
        if (offset <= literal.startOffset || offset > literal.endOffset) return false
        var quote = literal.startOffset
        while (quote < literal.endOffset && text[quote].isLetter()) quote++
        if (quote >= literal.endOffset || !FStrings.isInterpolated(text, literal.startOffset, quote)) return false
        // per field being read, where its expression starts, or CLOSED once a `!` or `:` has ended it
        val fields = ArrayDeque<Int>()
        var found = false
        FStrings.scan(text, literal.startOffset, literal.endOffset) { start, end, type ->
            when (type) {
                BasedPythonTokenTypes.FSTRING_LBRACE -> fields.addLast(end)
                BasedPythonTokenTypes.FSTRING_CONVERSION, BasedPythonTokenTypes.FSTRING_FORMAT_START -> {
                    val open = fields.removeLastOrNull()
                    if (open != null && open != CLOSED && offset in open..start) found = true
                    if (open != null) fields.addLast(CLOSED)
                }
                BasedPythonTokenTypes.FSTRING_RBRACE -> {
                    val open = fields.removeLastOrNull()
                    if (open != null && open != CLOSED && offset in open..start) found = true
                }
            }
        }
        // a field left open runs to the end of what is typed
        return found || fields.any { it != CLOSED && offset >= it }
    }

    /** The string token of [file] the caret at [offset] is just after, or null off one. */
    fun stringBefore(file: PsiFile, offset: Int): PsiElement? =
        (file as? BasedPythonFile)?.findElementAt(offset - 1)?.takeIf { it.node.elementType == BasedPythonTokenTypes.STRING }

    /** Whether the caret at [offset] of [file] is in a replacement field's expression. */
    fun isInFieldCode(file: PsiFile, offset: Int): Boolean {
        val literal = stringBefore(file, offset) ?: return false
        return inFieldCode(file.viewProvider.contents, literal.textRange, offset)
    }

    /** The identifier that ends at [offset] of [text], `car` in `f"{car‸`. */
    fun identifierBefore(text: CharSequence, offset: Int): String {
        var start = offset
        while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
        return text.subSequence(start, offset).toString()
    }

    /** A field whose expression has already ended: its `}` is still to come. */
    private const val CLOSED = -1
}

/** `by`'s completion, filtered inside a replacement field by the identifier before the caret. */
internal object ByCompletionSupport : LspCompletionSupport() {

    override fun getCompletionPrefix(parameters: CompletionParameters, defaultPrefix: String): String {
        val file = parameters.originalFile
        if (!ByFStringFields.isInFieldCode(file, parameters.offset)) return defaultPrefix
        return ByFStringFields.identifierBefore(file.viewProvider.contents, parameters.offset)
    }
}
