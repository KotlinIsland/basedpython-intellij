package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lang.StringLiteralShape
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Text pasted inside a `.by` string literal is escaped, so that the literal holds what was pasted:
 * a backslash stays a backslash, a quote does not end the literal, a line break in a one-line
 * literal becomes `\n`, and a brace in an f-string's text is doubled rather than opening an
 * interpolation.
 *
 * Text copied from inside a string literal is already written the way a literal writes it, and is
 * pasted into another one as it is — escaping it again would double every backslash. Copying marks
 * it: [preprocessOnCopy] hands back the text unchanged, which is what makes the platform keep the
 * text it copied as the paste's [RawText]. Such text that would not fit the literal it is pasted
 * into as it is — a line break in a one-line literal, that literal's own quote — is escaped all the
 * same.
 *
 * ## What is lexical here and what is not
 *
 * Where a literal's content starts and ends, its quotes and its prefix are the lexer's, and that is
 * all a plain literal needs. Inside an f-string it is not enough: whether the paste lands in the
 * literal's text, or inside an interpolation where it is code and must not be touched, is `by`'s
 * parse, and its outline says. Without an outline for the text on screen, a paste into an f-string
 * is left as it is.
 *
 * A raw literal cannot escape anything, so a paste into one is left as it is too.
 */
class BasedPythonStringPasteProcessor : CopyPastePreProcessor {

    override fun requiresAllDocumentsToBeCommitted(editor: Editor, project: Project): Boolean = false

    override fun preprocessOnCopy(file: PsiFile, startOffsets: IntArray, endOffsets: IntArray, text: String): String? {
        if (file !is BasedPythonFile || startOffsets.size != 1) return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        if (PsiDocumentManager.getInstance(file.project).isUncommited(document)) return null
        val start = startOffsets[0]
        val end = endOffsets[0]
        val token = file.findElementAt(start)
            ?.takeIf { it.node.elementType == BasedPythonTokenTypes.STRING }
            ?.textRange
        // the copy is a literal's text as it is written: unchanged, but marked as such
        return text.takeIf { StringLiteralAt.of(document.immutableCharSequence, token, start, end) != null }
    }

    override fun preprocessOnPaste(project: Project, file: PsiFile, editor: Editor, text: String, rawText: RawText?): String {
        if (file !is BasedPythonFile) return text
        val document = editor.document
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        val literal = StringLiteralAt.of(document.immutableCharSequence, stringToken(editor, start), start, end)
            ?: return text
        if (literal.shape.isRaw) return text
        if (literal.shape.isFString && !inFStringText(project, document, start, end)) return text

        val context = StringPasteEscaping.Context.of(document.immutableCharSequence, literal, start, end)
        // copied from inside a literal: already written as one, and pasted as it is when it fits
        if (rawText != null && rawText.rawText == text && StringPasteEscaping.fits(text, context)) return text
        return StringPasteEscaping.escape(text, context)
    }

    /**
     * The string token [offset] is in or at the end of, from the editor's lexer — which is current
     * whatever the state of the PSI, and is what draws the literal on screen.
     */
    private fun stringToken(editor: Editor, offset: Int): TextRange? {
        for (at in listOf(offset, offset - 1)) {
            if (at < 0 || at >= editor.document.textLength) continue
            val iterator = editor.highlighter.createIterator(at)
            if (!iterator.atEnd() && iterator.tokenType == BasedPythonTokenTypes.STRING) {
                return TextRange(iterator.start, iterator.end)
            }
        }
        return null
    }

    /**
     * Whether [start] to [end] is in the text of an f-string rather than in one of its
     * interpolations, as `by`'s outline of the document has it; false without an outline.
     */
    private fun inFStringText(project: Project, document: Document, start: Int, end: Int): Boolean {
        val outline = ByOutlines.getInstance(project).current(document) ?: return false
        val part = outline.strings.firstOrNull { it.range.startOffset <= start && end <= it.range.endOffset }
            ?: return false
        return part.interpolations.none { interpolation ->
            if (start == end) {
                interpolation.startOffset < start && start < interpolation.endOffset
            } else {
                interpolation.startOffset < end && start < interpolation.endOffset
            }
        }
    }
}

/** A string literal, and a range lying within its content. */
internal class StringLiteralAt private constructor(
    /** The whole literal, prefix and quotes included. */
    val token: TextRange,
    val shape: StringLiteralShape,
) {
    /** The content's range in the document. */
    val content: TextRange get() = TextRange(token.startOffset + shape.contentStart, token.startOffset + shape.contentEnd)

    companion object {

        /**
         * The literal [token] is, when [start] to [end] lies within its content — between the
         * quotes, either end touching them; null otherwise.
         */
        fun of(text: CharSequence, token: TextRange?, start: Int, end: Int): StringLiteralAt? {
            token ?: return null
            val shape = StringLiteralShape.of(token.subSequence(text)) ?: return null
            val literal = StringLiteralAt(token, shape)
            return literal.takeIf { start >= it.content.startOffset && end <= it.content.endOffset }
        }
    }
}

/** How text is written inside a string literal so that the literal holds it. */
internal object StringPasteEscaping {

    /** The literal pasted into, and what is on either side of the paste inside it. */
    data class Context(
        val quote: Char,
        val isTriple: Boolean,
        val isFString: Boolean,
        /** How many of the literal's quote characters come just before the paste, inside it. */
        val quotesBefore: Int,
        /** How many come just after it, the closing quotes included. */
        val quotesAfter: Int,
    ) {
        companion object {
            fun of(text: CharSequence, literal: StringLiteralAt, start: Int, end: Int): Context {
                val quote = literal.shape.quote
                var before = 0
                while (start - before - 1 >= literal.content.startOffset && text[start - before - 1] == quote) before++
                var after = 0
                while (end + after < literal.token.endOffset && text[end + after] == quote) after++
                return Context(quote, literal.shape.isTriple, literal.shape.isFString, before, after)
            }
        }
    }

    /** [text] written so that, pasted where [context] says, the literal holds exactly [text]. */
    fun escape(text: String, context: Context): String {
        // In a triple-quoted literal a quote only needs escaping where it would make three in a
        // row, or run into the quotes after the paste — the closing ones among them.
        val trailingQuotes = text.length - text.takeLastWhile { it == context.quote }.length
        val out = StringBuilder(text.length + 8)
        var run = context.quotesBefore
        for ((index, char) in text.withIndex()) {
            if (char == context.quote) {
                val ends = context.isTriple && context.quotesAfter > 0 && index >= trailingQuotes
                if (!context.isTriple || run == 2 || ends) {
                    out.append('\\').append(char)
                    run = 0
                } else {
                    out.append(char)
                    run++
                }
                continue
            }
            run = 0
            when {
                char == '\\' -> out.append("\\\\")
                char == '\n' && !context.isTriple -> out.append("\\n")
                char == '\r' && !context.isTriple -> out.append("\\r")
                (char == '{' || char == '}') && context.isFString -> out.append(char).append(char)
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    /**
     * Whether [text], already written as a literal's content, can go where [context] says as it
     * is: nothing in it ends the literal, or in a one-line literal the line.
     */
    fun fits(text: String, context: Context): Boolean {
        var escaped = false
        var run = context.quotesBefore
        for (char in text) {
            if (escaped) {
                escaped = false
                run = 0
                continue
            }
            when (char) {
                '\\' -> escaped = true
                '\n', '\r' -> if (!context.isTriple) return false
                context.quote -> {
                    if (!context.isTriple || ++run >= 3) return false
                    continue
                }
            }
            run = 0
        }
        // a backslash left over at the end would escape the quote after the paste
        if (escaped) return false
        return !(context.isTriple && run > 0 && run + context.quotesAfter >= 3)
    }
}
