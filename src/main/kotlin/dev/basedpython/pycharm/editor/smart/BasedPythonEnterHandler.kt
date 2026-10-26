package dev.basedpython.pycharm.editor.smart

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.editor.indent.BasedPythonLineIndentProvider
import dev.basedpython.pycharm.editor.indent.ByIndents
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Enter in a `.by` file, keeping the code what it was: [ByIndents.lineBreak] says where the new
 * line goes and what the break has to add — a backslash inside a statement, the quotes of a split
 * string and the parentheses around it, a comment's `#`, a line of its own for the closing bracket
 * of an empty pair.
 *
 * Every answer is about the text `by`'s outline describes — see [ByOutlines] — so it is worked out
 * here, before the line break goes in and the outline stops matching the text. The indentation
 * itself is then the platform's to apply, asked of [BasedPythonLineIndentProvider] for the new line
 * like any language's, and only under *Smart indent* like any language's; what is added after it is
 * this handler's, once the platform has broken the line.
 *
 * With no outline for the text on screen — the key was pressed before the server answered for the
 * last edit — this does nothing and the platform carries the current line's indentation onto the
 * new one, since this runs on the EDT and does not wait. It never re-derives the answer from the
 * text instead.
 */
class BasedPythonEnterHandler : EnterHandlerDelegate {

    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        pending = null
        BasedPythonLineIndentProvider.forget()
        if (file !is BasedPythonFile) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val outline = ByOutlines.getInstance(file.project).current(document)
            ?: return EnterHandlerDelegate.Result.Continue
        val indents = ByIndents(outline, document.immutableCharSequence, CodeStyle.getIndentOptions(file))
        val lineBreak = indents.lineBreak(caretOffset.get()) ?: return EnterHandlerDelegate.Result.Continue

        var caret = lineBreak.at ?: caretOffset.get()
        lineBreak.closeOnOwnLine?.let { closing ->
            // Between an empty pair, both lines are this handler's: the platform's own handling of
            // a brace pair would break it again and indent the closing line as the new one.
            val text = document.immutableCharSequence
            var closer = caret
            while (closer < text.length && (text[closer] == ' ' || text[closer] == '\t')) closer++
            val inner = indents.whitespace(lineBreak.indent)
            document.replaceString(caret, closer, "\n" + inner + "\n" + indents.whitespace(closing))
            editor.caretModel.moveToOffset(caret + 1 + inner.length)
            return EnterHandlerDelegate.Result.Stop
        }
        lineBreak.wrap?.let { run ->
            document.insertString(run.endOffset, ")")
            document.insertString(run.startOffset, "(")
            if (caret > run.startOffset) caret++
        }
        if (lineBreak.before.isNotEmpty()) {
            document.insertString(caret, lineBreak.before)
            caret += lineBreak.before.length
        }
        caretOffset.set(caret)
        BasedPythonLineIndentProvider.expect(
            document,
            line = document.getLineNumber(caret) + 1,
            indent = indents.whitespace(lineBreak.indent),
        )
        if (lineBreak.after.isNotEmpty()) pending = Pending(document, lineBreak.after)
        return EnterHandlerDelegate.Result.Continue
    }

    override fun postProcessEnter(
        file: PsiFile,
        editor: Editor,
        dataContext: DataContext,
    ): EnterHandlerDelegate.Result {
        val pending = pending ?: return EnterHandlerDelegate.Result.Continue
        this.pending = null
        BasedPythonLineIndentProvider.forget()
        val document = editor.document
        if (pending.document.get() !== document) return EnterHandlerDelegate.Result.Continue

        val caret = editor.caretModel.offset
        document.insertString(caret, pending.after)
        editor.caretModel.moveToOffset(caret + pending.after.length)
        return EnterHandlerDelegate.Result.Continue
    }

    /**
     * What is left to add once the platform has broken the line, worked out before it did. Read back
     * in [postProcessEnter], within the same Enter on the EDT. The document is held weakly: an
     * Enter the platform abandons between the two calls never clears this, and a closed file's
     * document must not stay reachable from an extension.
     */
    private var pending: Pending? = null

    private class Pending(document: Document, val after: String) {
        val document = java.lang.ref.WeakReference(document)
    }
}
