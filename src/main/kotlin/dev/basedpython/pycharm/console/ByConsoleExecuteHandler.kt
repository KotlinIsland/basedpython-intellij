package dev.basedpython.pycharm.console

import com.intellij.application.options.CodeStyle
import com.intellij.execution.console.BaseConsoleExecuteActionHandler
import com.intellij.execution.console.ConsoleExecuteAction
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Condition
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import dev.basedpython.pycharm.editor.indent.ByIndents
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.ByInputStatus
import dev.basedpython.pycharm.lsp.ext.ByInputStatusParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineParams
import dev.basedpython.pycharm.lsp.outline.ByOutlineReplies
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger.getInstance(ByConsoleExecuteHandler::class.java)

/**
 * Enter in a basedpython console: runs the input when it is whole, and starts another line of it when
 * it goes on.
 *
 * Which of the two is `by/inputStatus`'s answer for what has been typed — `complete` and `invalid`
 * run, `invalid` so that `by repl` says what is wrong; `incomplete` breaks the line where the caret
 * is, indented as Enter in a `.by` file would indent it ([ByIndents], over `by`'s outline of the
 * session module ending in the input). The question is asked off the EDT and answered on it, and an
 * answer that arrives after the input has changed is dropped: the Enter was about other text.
 *
 * A `by` that cannot answer — one from before the request, or none running — leaves Enter breaking
 * the line, and the console says once that *Console.Execute.Multiline* runs the input whatever it
 * holds, which it always does ([registerRunNow]).
 */
internal class ByConsoleExecuteHandler(internal val session: ByConsoleSession) : BaseConsoleExecuteActionHandler(false) {

    /** Whether the console has said that Enter cannot ask `by`; said once a session. */
    private val saidUnanswered = AtomicBoolean()

    /** Set by the run-now action for the one run it starts. */
    private var runningNow = false

    override fun runExecuteAction(consoleView: LanguageConsoleView) {
        if (runningNow) return super.runExecuteAction(consoleView)
        val editor = consoleView.consoleEditor
        val document = editor.document
        val stamp = document.modificationStamp
        val text = document.text
        val caret = editor.caretModel.offset
        val options = CodeStyle.getIndentOptions(consoleView.file ?: return)
        val modality = ModalityState.stateForComponent(editor.component).asContextElement()
        scope().launch {
            val enter = enter(text, caret, options)
            withContext(Dispatchers.EDT + modality) {
                if (editor.isDisposed || document.modificationStamp != stamp) return@withContext
                when (enter) {
                    Enter.Run -> runNow(consoleView)
                    is Enter.Break -> WriteCommandAction.runWriteCommandAction(session.project, null, null, {
                        document.replaceString(0, document.textLength, enter.edit.text)
                        editor.caretModel.moveToOffset(enter.edit.caret)
                        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
                    })
                }
            }
        }
    }

    /** Runs what is typed now, without asking `by`. On the EDT. */
    fun runNow(consoleView: LanguageConsoleView) {
        runningNow = true
        try {
            super.runExecuteAction(consoleView)
        } finally {
            runningNow = false
        }
    }

    override fun execute(text: String, console: LanguageConsoleView) {
        session.send(text)
    }

    /** What Enter does with [text], the caret at [caret]: `by`'s answer, or a plain line break when it gives none. */
    private suspend fun enter(text: String, caret: Int, options: CommonCodeStyleSettings.IndentOptions): Enter {
        val by = session.runningBy()
        val answer = by?.awaitBy("by/inputStatus") {
            (it as ByServerExtensions).inputStatus(ByInputStatusParams(ByConsoleText.statusText(text)))
        } ?: ByAnswer.Failed
        val status = (answer as? ByAnswer.Answer<ByInputStatus>)?.value?.status
        if (status == null) {
            if (saidUnanswered.compareAndSet(false, true)) {
                val shortcut = KeymapUtil.getFirstKeyboardShortcutText(RUN_NOW_ACTION_ID)
                withContext(Dispatchers.EDT) { session.note(BasedPythonBundle.message("console.noInputStatus", shortcut)) }
            }
            return Enter.Break(ByConsoleText.brokenPlainly(text, caret))
        }
        if (status != INCOMPLETE) return Enter.Run
        return Enter.Break(lineBreak(text, caret, options) ?: ByConsoleText.brokenPlainly(text, caret))
    }

    /**
     * The line break Enter makes at [caret] in [input], from `by`'s outline of the session module that
     * ends in it; null when `by` has no outline to give.
     */
    private suspend fun lineBreak(input: String, caret: Int, options: CommonCodeStyleSettings.IndentOptions): ByConsoleText.Edit? {
        val by = session.runningBy() ?: return null
        val source = session.sessionSource()
        val module = source + input
        session.document.sync(by, module)
        val params = BySyntaxOutlineParams(TextDocumentIdentifier(session.document.uri), ByTextHash.of(module))
        val reply = by.awaitBy("by/syntaxOutline") { (it as ByServerExtensions).syntaxOutline(params) }.value ?: return null
        val document = EditorFactory.getInstance().createDocument(module)
        val outline = readAction { ByOutlineReplies.read(reply, document) } ?: return null
        val indents = ByIndents(outline, module, options)
        val lineBreak = indents.lineBreak(source.length + caret) ?: return null
        return ByConsoleText.broken(input, caret, source.length, lineBreak, indents::whitespace)
    }

    private fun scope(): CoroutineScope = session.project.service<ByConsoles>().scope

    private sealed interface Enter {
        data object Run : Enter
        class Break(val edit: ByConsoleText.Edit) : Enter
    }

    companion object {
        /** The platform's run-whatever-is-typed shortcut in a console, Ctrl+Enter / ⌘↩. */
        const val RUN_NOW_ACTION_ID = "Console.Execute.Multiline"

        private const val INCOMPLETE = "incomplete"

        /** Gives [console] the action that runs the input without asking `by`, on [RUN_NOW_ACTION_ID]'s shortcut. */
        fun registerRunNow(console: LanguageConsoleView, handler: ByConsoleExecuteHandler) {
            val runNow = object : BaseConsoleExecuteActionHandler(false) {
                override fun runExecuteAction(consoleView: LanguageConsoleView) = handler.runNow(consoleView)
            }
            val action = ConsoleExecuteAction(console, runNow, RUN_NOW_ACTION_ID, Condition { handler.session.isRunning })
            action.registerCustomShortcutSet(action.shortcutSet, console.consoleEditor.component)
        }
    }
}
