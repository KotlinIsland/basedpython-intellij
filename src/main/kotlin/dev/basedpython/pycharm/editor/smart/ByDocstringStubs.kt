package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByDocstringStub
import dev.basedpython.pycharm.lsp.ext.ByDocstringStubParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting

/**
 * `"""` and Enter at the top of a function's body in a `.by` file write its docstring stub, as
 * *Insert documentation comment stub* does for `.py`: the quotes are closed, and the empty
 * docstring is filled with what `by/docstringStub` answers — a summary line for the caret, the
 * parameters, what the function returns.
 *
 * What the stub says, and whether the literal is a function's docstring at all, is `by`'s; this
 * only recognises, from the text, the line Enter has just left: an opening `"""` or `'''` on a line
 * of its own, with the docstring empty after it — closed already (the quote handler closes a triple
 * quote as it is typed) or not closed at all, in which case its closing quotes are written on a
 * line of their own, as `.py`'s Enter does. `by` is asked off the EDT once the line is broken; the
 * stub is written only if the text and caret are as they were, and not at all by a `by` without
 * the request or where it answers `null`.
 *
 * The format is the one the file's other docstrings are written in, `by`'s choice: PyCharm's own
 * *Docstring format* setting belongs to the Python plugin, which this plugin does not depend on.
 */
class ByDocstringEnterHandler : EnterHandlerDelegate {

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (file !is BasedPythonFile || !CodeInsightSettings.getInstance().JAVADOC_STUB_ON_ENTER) {
            return EnterHandlerDelegate.Result.Continue
        }
        if (editor.caretModel.caretCount > 1) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val caret = editor.caretModel.offset
        val highlighter = editor.highlighter
        val opened = ByDocstringStubs.openedAt(document.immutableCharSequence, caret) { highlighter.createIterator(it).start }
            ?: return EnterHandlerDelegate.Result.Continue
        if (opened.closing == null) document.insertString(caret, "\n" + opened.indent + opened.quotes)
        val virtualFile = file.virtualFile ?: return EnterHandlerDelegate.Result.Continue
        val server = byServerFor(file.project, virtualFile) ?: return EnterHandlerDelegate.Result.Continue
        file.project.service<ByDocstringStubs>().fill(editor) { doc, offset -> ByDocstringStubs.ask(server, virtualFile, doc, offset) }
        return EnterHandlerDelegate.Result.Continue
    }
}

/**
 * Where the stub is asked for and written: cancelled with the project, and with the plugin when
 * it unloads.
 */
@Service(Service.Level.PROJECT)
class ByDocstringStubs(private val project: Project, private val scope: CoroutineScope) {

    /** The opening quotes Enter has just left, on a line of their own. */
    data class Opened(
        /** The opening line's indentation, which closing quotes written for it take. */
        val indent: String,
        /** `"""` or `'''`. */
        val quotes: String,
        /** Where the docstring's closing quotes start; `null` when it is not closed. */
        val closing: Int?,
    )

    /**
     * Asks [find] for the stub at [editor]'s caret off the EDT, and writes it on the EDT — unless by
     * then the editor is gone, or its caret or text has moved.
     */
    @VisibleForTesting
    fun fill(editor: Editor, find: suspend (Document, Int) -> ByDocstringStub?): Job {
        val document = editor.document
        val offset = editor.caretModel.offset
        val stamp = document.modificationStamp
        return scope.launch {
            val stub = find(document, offset) ?: return@launch
            withContext(Dispatchers.EDT) {
                val stillThere = !editor.isDisposed && document.modificationStamp == stamp && editor.caretModel.offset == offset
                if (stillThere) write(editor, stub)
            }
        }
    }

    private fun write(editor: Editor, stub: ByDocstringStub) {
        val document = editor.document
        val range = stub.range?.let { getRangeInDocument(document, it) } ?: return
        val text = stub.newText ?: return
        WriteCommandAction.writeCommandAction(project).withName(COMMAND).run<RuntimeException> {
            document.replaceString(range.startOffset, range.endOffset, text)
            val caret = stub.caret?.let { getOffsetInDocument(document, it) } ?: return@run
            editor.caretModel.moveToOffset(caret)
            editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
    }

    companion object {
        private const val COMMAND = "Insert Docstring Stub"

        /**
         * The opening quotes of an empty docstring, when [caret] is on the line after a line that is
         * nothing but them — `"""`, `'''`, or either after an `r` or `u` prefix — and nothing but
         * whitespace follows them up to their closing quotes or, when the literal does not close
         * before code, up to the end of the caret's line. [tokenStart] is where the lexer's token
         * at an offset starts: quotes a token starts at open a literal, where the closing quotes of
         * one are in the middle of its token.
         */
        fun openedAt(text: CharSequence, caret: Int, tokenStart: (Int) -> Int): Opened? {
            val lineStart = lineStart(text, caret)
            if (lineStart == 0 || text.subSequence(lineStart, caret).isNotBlank()) return null
            val previousStart = lineStart(text, lineStart - 1)
            val previous = text.subSequence(previousStart, lineStart - 1).toString()
            val indent = previous.takeWhile { it == ' ' || it == '\t' }
            val opening = previous.substring(indent.length).trimEnd()
            val quotes = opening.takeLast(3).takeIf { it == "\"\"\"" || it == "'''" } ?: return null
            if (opening.dropLast(3).lowercase() !in PREFIXES) return null
            val literalStart = previousStart + indent.length
            if (tokenStart(literalStart) != literalStart) return null
            // the rest of the literal: whitespace up to its closing quotes, or code it would swallow
            var at = caret
            while (at < text.length && text[at].isWhitespace()) at++
            if (text.startsWith(quotes, at)) return Opened(indent, quotes, at)
            val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
            return if (text.subSequence(caret, lineEnd).isBlank()) Opened(indent, quotes, null) else null
        }

        /** What `by` answers for the empty docstring at [offset] of [document]; `null` for no stub, whatever the reason. */
        suspend fun ask(server: LspClient, file: VirtualFile, document: Document, offset: Int): ByDocstringStub? {
            val params = readAction {
                ByServerDocuments.ensureOpen(server, server.project, file)
                ByDocstringStubParams(server.getDocumentIdentifier(file), getLsp4jPosition(document, offset), ByTextHash.of(document.immutableCharSequence))
            }
            val answer = awaitingAgain<ByDocstringStub>("by/docstringStub", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
                server.sendRequest { (it as ByServerExtensions).docstringStub(params).also(sent) }
            }
            // a `by` from before the request fails it as MethodNotFound: no stub, as before it
            return (answer as? ByAnswer.Answer)?.value
        }

        private val PREFIXES = setOf("", "r", "u")

        private fun lineStart(text: CharSequence, offset: Int): Int {
            var at = offset
            while (at > 0 && text[at - 1] != '\n') at--
            return at
        }
    }
}
