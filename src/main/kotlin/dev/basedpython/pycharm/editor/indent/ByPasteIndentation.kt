package dev.basedpython.pycharm.editor.indent

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.formatting.IndentInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonHighlightingLexer

/**
 * Lines pasted into a `.by` file at the start of a line move as a block: the first goes where the
 * caret is — or, pasted in front of a line's code, where that code is — and every other line keeps
 * its indentation relative to the first, as PyCharm does for `.py`. Under *Smart indent pasted
 * lines* (`CodeInsightSettings.INDENT_TO_CARET_ON_PASTE`), the switch Python's paste reads.
 *
 * The platform's own block indenting cannot be left to it: *Indent block* shifts every line after
 * the first by the caret's column whatever the first was copied at, and the platform's
 * preserve-indent-on-paste keeps the copied indentation of a first line pasted onto an indented
 * blank line, so an `if` block copied from one suite and pasted into another lands outside the `if`
 * or too deep. The text is rewritten whole instead, before it goes in; the platform's indenting
 * that follows a rewritten paste asks [ByIndentFormattingModelBuilder], whose outline is then a
 * revision old and which leaves the lines as they are.
 *
 * This is lexical — where the caret is in its line and how the copied lines are indented relative
 * to one another — so it needs no outline. Text pasted into the middle of a line, or into a string,
 * is left as it is.
 */
class ByPasteIndentation : CopyPastePreProcessor {

    override fun requiresAllDocumentsToBeCommitted(editor: Editor, project: Project): Boolean = false

    override fun preprocessOnCopy(file: PsiFile, startOffsets: IntArray, endOffsets: IntArray, text: String): String? = null

    override fun preprocessOnPaste(project: Project, file: PsiFile, editor: Editor, text: String, rawText: RawText?): String {
        if (file !is BasedPythonFile) return text
        if (!CodeInsightSettings.getInstance().INDENT_TO_CARET_ON_PASTE) return text
        if (!text.contains('\n') || editor.caretModel.caretCount > 1) return text
        val document = editor.document
        val chars = document.immutableCharSequence
        val at = editor.selectionModel.selectionStart
        val lineStart = document.getLineStartOffset(document.getLineNumber(at))
        if (!chars.subSequence(lineStart, at).isBlank()) return text
        if (insideString(editor, at)) return text
        return reindent(text, chars, lineStart, at, CodeStyle.getIndentOptions(file))
    }

    private fun insideString(editor: Editor, offset: Int): Boolean {
        if (offset >= editor.document.textLength) return false
        val iterator = editor.highlighter.createIterator(offset)
        if (iterator.atEnd()) return false
        val literal = BasedPythonHighlightingLexer.literalAt(iterator) ?: return false
        return literal.startOffset < offset
    }

    companion object {

        /**
         * [text] as it is to be inserted at [at], whose line starts at [lineStart] in [document] and
         * has only whitespace before [at].
         */
        internal fun reindent(
            text: String,
            document: CharSequence,
            lineStart: Int,
            at: Int,
            options: CommonCodeStyleSettings.IndentOptions,
        ): String {
            val lines = text.split('\n')
            val written = lines.map { columns(it, options.TAB_SIZE) }
            val code = lines.indices.filter { lines[it].isNotBlank() }
            if (code.isEmpty()) return text
            val base = code.minOf { written[it] }

            val caretColumn = columns(document.subSequence(lineStart, at), options.TAB_SIZE)
            var lineEnd = at
            while (lineEnd < document.length && document[lineEnd] != '\n') lineEnd++
            val codeAfter = document.subSequence(at, lineEnd).isNotBlank()
            // in front of a line's code the block goes where the code is, and the code stays there
            if (codeAfter && !text.endsWith('\n')) return text
            val target = if (codeAfter) columns(document.subSequence(lineStart, lineEnd), options.TAB_SIZE) else caretColumn

            return buildString {
                for ((i, line) in lines.withIndex()) {
                    if (i > 0) append('\n')
                    val content = line.trimStart(' ', '\t')
                    when {
                        // after the text's last line break come what was after the caret and the
                        // line's own code: the indentation that was before the caret goes here
                        i == lines.lastIndex && codeAfter -> append(whitespace(caretColumn, options))
                        content.isEmpty() -> Unit
                        // the whitespace before the caret is already in front of the first line
                        i == 0 -> append(whitespace(target - caretColumn, options)).append(content)
                        else -> append(whitespace(target + written[i] - base, options)).append(content)
                    }
                }
            }
        }

        private fun columns(line: CharSequence, tabSize: Int): Int {
            var column = 0
            for (c in line) {
                column = when (c) {
                    ' ' -> column + 1
                    '\t' -> if (tabSize > 0) (column / tabSize + 1) * tabSize else column
                    else -> return column
                }
            }
            return column
        }

        private fun whitespace(columns: Int, options: CommonCodeStyleSettings.IndentOptions): String =
            IndentInfo(0, maxOf(columns, 0), 0).generateNewWhiteSpace(options)
    }
}
