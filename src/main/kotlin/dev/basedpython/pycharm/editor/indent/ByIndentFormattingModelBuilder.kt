package dev.basedpython.pycharm.editor.indent

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingMode
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * The platform's formatter for `.by`, for indentation only: where End puts the caret on a blank line
 * and what Auto-Indent Lines (Ctrl+Alt+I) does, both of which the platform asks of a formatting
 * model and offers no other way to answer — Auto-Indent Lines is not even enabled for a language
 * without one.
 *
 * Every line's place is [ByIndents]' answer, over `by`'s outline of the text as it is now. When the
 * outline is for some other revision there is no answer, and every line keeps the indentation it
 * has. Formatting itself is buff's (`BuffFormattingService`): in every mode but adjusting
 * indentation the model changes nothing — no spacing, no line breaks — so the platform's formatter,
 * which the IDE falls back to for a fragment buff does not format, leaves `.by` text as it is, as it
 * did before this existed.
 *
 * The model is flat: a block per line of code — a string or comment spanning lines stays one — with
 * the indentation of each line in the range asked about read off [ByIndents].
 */
class ByIndentFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(context: FormattingContext): FormattingModel {
        val file = context.containingFile
        val text = file.viewProvider.contents
        val options = CodeStyle.getIndentOptions(file)
        val indents = if (context.formattingMode == FormattingMode.REFORMAT) null else {
            file.viewProvider.document?.let { document ->
                ByOutlines.getInstance(file.project).current(document)
                    ?.takeIf { document.immutableCharSequence.contentEquals(text) }
                    ?.let { ByIndents(it, text, options) }
            }
        }
        val root = Lines(text, indents, context.formattingRange, options.TAB_SIZE)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, context.codeStyleSettings)
    }

    /** The file: a block per line, flat. */
    private class Lines(
        private val text: CharSequence,
        private val indents: ByIndents?,
        private val range: TextRange,
        private val tabSize: Int,
    ) : Block {

        private val lines: List<Line> by lazy(LazyThreadSafetyMode.NONE) {
            lineRanges().map { Line(it, indentOf(it.startOffset)) }
        }

        /**
         * Where each line's code is: from its first token to the end of the last token that starts on
         * it, so a string or comment running onto later lines stays in the line it starts on.
         */
        private fun lineRanges(): List<TextRange> {
            val ranges = ArrayList<TextRange>()
            val lexer = BasedPythonLexer()
            lexer.start(text, 0, text.length, 0)
            var start = -1
            var end = -1
            while (lexer.tokenType != null) {
                if (lexer.tokenType != BasedPythonTokenTypes.WHITESPACE) {
                    if (start >= 0 && lineBreakBetween(end, lexer.tokenStart)) {
                        ranges += TextRange(start, end)
                        start = -1
                    }
                    if (start < 0) start = lexer.tokenStart
                    end = lexer.tokenEnd
                }
                lexer.advance()
            }
            if (start >= 0) ranges += TextRange(start, end)
            return ranges
        }

        private fun lineBreakBetween(from: Int, to: Int): Boolean = (from until to).any { text[it] == '\n' || text[it] == '\r' }

        /** The indent of the line starting at [start]: [ByIndents]' answer when it is in the range asked about. */
        private fun indentOf(start: Int): Int? {
            val indents = indents ?: return null
            val lineStart = lineStart(start)
            if (lineStart > range.endOffset || lineEnd(start) < range.startOffset) return null
            return indents.lineIndent(lineStart)
        }

        private fun lineStart(offset: Int): Int {
            var i = offset
            while (i > 0 && text[i - 1] != '\n' && text[i - 1] != '\r') i--
            return i
        }

        private fun lineEnd(offset: Int): Int {
            var i = offset
            while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
            return i
        }

        /** The columns of the leading whitespace of the line holding [offset], as written. */
        private fun writtenIndent(offset: Int): Int {
            var column = 0
            for (i in lineStart(offset) until text.length) {
                when (text[i]) {
                    ' ' -> column++
                    '\t' -> column = if (tabSize > 0) (column / tabSize + 1) * tabSize else column
                    else -> return column
                }
            }
            return column
        }

        override fun getTextRange(): TextRange = TextRange(0, text.length)
        override fun getSubBlocks(): List<Block> = lines
        override fun getWrap(): Wrap? = null
        override fun getIndent(): Indent = Indent.getNoneIndent()
        override fun getAlignment(): Alignment? = null

        /**
         * A line whose indentation is answered may have it changed, keeping the line breaks before
         * it as they are; any other line is left as it is.
         */
        override fun getSpacing(child1: Block?, child2: Block): Spacing {
            if (child1 == null || (child2 as Line).columns == null) return Spacing.getReadOnlySpacing()
            val lineBreaks = (child1.textRange.endOffset until child2.textRange.startOffset).count { text[it] == '\n' }
            return Spacing.createSpacing(0, Int.MAX_VALUE, lineBreaks, true, lineBreaks)
        }

        /** A new line — End on a blank line — goes where [ByIndents] puts the line asked about, or stays as it is. */
        override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
            val columns = indents?.lineIndent(lineStart(range.startOffset)) ?: writtenIndent(range.startOffset)
            return ChildAttributes(Indent.getSpaceIndent(columns), null)
        }

        override fun isIncomplete(): Boolean = false
        override fun isLeaf(): Boolean = false

        /** One line's code, at [columns] when [ByIndents] answered for it, else where it is written. */
        private inner class Line(private val range: TextRange, val columns: Int?) : Block {
            override fun getTextRange(): TextRange = range
            override fun getSubBlocks(): List<Block> = emptyList()
            override fun getWrap(): Wrap? = null
            override fun getIndent(): Indent = Indent.getSpaceIndent(columns ?: writtenIndent(range.startOffset))
            override fun getAlignment(): Alignment? = null
            override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
            override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(Indent.getNoneIndent(), null)
            override fun isIncomplete(): Boolean = false
            override fun isLeaf(): Boolean = true
        }
    }
}
