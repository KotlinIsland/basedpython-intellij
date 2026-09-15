package dev.basedpython.pycharm.editor.smart

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.formatting.IndentInfo
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import java.lang.ref.WeakReference

/**
 * Enter in a `.by` file: a line broken right after a suite's `:` starts the suite, one indent deeper
 * than the line its clause keyword is on.
 *
 * Whether the caret is there is `by`'s answer, not this handler's — see [ByOutlines]. A `:` in a
 * string, a dict, a slice or a lambda opens nothing, and `match: int = 1` is not a match statement,
 * which a line that merely ends in a colon cannot tell apart.
 *
 * Everything else is the platform's Enter, which carries the current line's indentation onto the
 * new one. That is also what happens when the outline is not for the text on screen — the key was
 * pressed before the server answered for the last edit — since this runs on the EDT and does not
 * wait. It never re-derives the answer from the text instead.
 *
 * The indent itself is the file's code style: [CodeStyle.getIndentOptions] says how wide a level
 * is and whether it is written with tabs, and the header's own indentation is measured in columns,
 * so a tab-indented header gets a tab-indented suite.
 */
class BasedPythonEnterHandler : EnterHandlerDelegate {

    /**
     * The indent worked out before the line break went in, when the document still is the revision
     * the outline describes. Read back in [postProcessEnter], within the same Enter on the EDT. The
     * document is held weakly: an Enter the platform abandons between the two calls never clears
     * this, and a closed file's document must not stay reachable from an extension.
     */
    private var pending: Pending? = null

    private class Pending(document: Document, val indent: String) {
        val document = WeakReference(document)
    }

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        pending = null
        if (file !is BasedPythonFile) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val outline = ByOutlines.getInstance(file.project).current(document)
            ?: return EnterHandlerDelegate.Result.Continue
        val clause = suiteOpenedAt(outline, document, caretOffset.get())
            ?: return EnterHandlerDelegate.Result.Continue

        val options = CodeStyle.getIndentOptions(file)
        val headerLine = document.getLineNumber(clause.keyword?.startOffset ?: clause.range.startOffset)
        val headerColumns = columns(document, headerLine, options.TAB_SIZE)
        val indent = IndentInfo(0, headerColumns + options.INDENT_SIZE, 0).generateNewWhiteSpace(options)
        pending = Pending(document, indent)
        return EnterHandlerDelegate.Result.Continue
    }

    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext,
    ): EnterHandlerDelegate.Result {
        val pending = pending ?: return EnterHandlerDelegate.Result.Continue
        this.pending = null
        val document = editor.document
        if (pending.document.get() !== document) return EnterHandlerDelegate.Result.Continue

        // The platform has broken the line and put its own indentation on the new one; that
        // indentation is what is replaced.
        val caret = editor.caretModel.offset
        val lineStart = document.getLineStartOffset(document.getLineNumber(caret))
        val text = document.immutableCharSequence
        var indentEnd = lineStart
        while (indentEnd < text.length && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) indentEnd++
        document.replaceString(lineStart, indentEnd, pending.indent)
        editor.caretModel.moveToOffset(lineStart + pending.indent.length)
        return EnterHandlerDelegate.Result.Stop
    }

    companion object {

        /**
         * The clause whose suite a line break at [offset] starts: its `:` is before the offset on
         * the same line, and nothing of its body comes before the offset — so `if x:|` and
         * `if x:| return` qualify, and `if x: return|` does not.
         */
        internal fun suiteOpenedAt(outline: ByOutline, document: Document, offset: Int): ByOutline.Clause? {
            val line = document.getLineNumber(offset)
            var found: ByOutline.Clause? = null
            outline.walk { statement, _ ->
                for (clause in statement.clauses) {
                    val colon = clause.colon ?: continue
                    if (colon.endOffset > offset || document.getLineNumber(colon.endOffset) != line) continue
                    val bodyStart = clause.body.firstOrNull()?.range?.startOffset
                    if (bodyStart != null && bodyStart < offset) continue
                    found = clause
                }
            }
            return found
        }

        /** How many columns the leading whitespace of [line] spans, a tab reaching the next stop. */
        internal fun columns(document: Document, line: Int, tabSize: Int): Int {
            val text = document.immutableCharSequence
            var column = 0
            var i = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            while (i < end) {
                when (text[i]) {
                    ' ' -> column++
                    '\t' -> column = if (tabSize > 0) (column / tabSize + 1) * tabSize else column
                    else -> return column
                }
                i++
            }
            return column
        }
    }
}
