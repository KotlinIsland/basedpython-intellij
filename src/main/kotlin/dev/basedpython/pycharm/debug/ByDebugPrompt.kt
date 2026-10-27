package dev.basedpython.pycharm.debug

import com.intellij.execution.console.BaseConsoleExecuteActionHandler
import com.intellij.execution.console.LanguageConsoleBuilder
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.platform.dap.DapEvaluationContext
import com.intellij.platform.dap.DapEvaluationResult
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.ui.OnePixelSplitter
import com.intellij.xdebugger.XDebugSession
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent

/**
 * A basedpython prompt under a `.by` debug session's console, as `.py`'s debug console has its Python
 * prompt: what is typed is evaluated in the frame selected in the Frames view, as the adapter's
 * `evaluate` with the `repl` context, and the value printed under it.
 *
 * The console above it is the run's own, and what is typed there is the program's standard input, as
 * in a run. Evaluation asks for a stopped frame; while the program runs, the prompt says so.
 */
internal class ByDebugPrompt(private val session: XDebugSession, private val scope: CoroutineScope) : Disposable {

    private val handler = object : BaseConsoleExecuteActionHandler(false) {
        override fun execute(text: String, console: LanguageConsoleView) = evaluate(text)
    }

    val console: LanguageConsoleView = LanguageConsoleBuilder()
        .initActions(handler, HISTORY_TYPE)
        .build(session.project, BasedPythonLanguage)
        .also { it.prompt = PROMPT }

    init {
        Disposer.register(this, console)
    }

    /** Evaluates [text] in the selected frame and prints the value, or why there is none. On the EDT. */
    fun evaluate(text: String) {
        if (text.isBlank()) return
        val frame = session.currentStackFrame as? DefaultDapXStackFrame
        if (frame == null) {
            console.print(BasedPythonBundle.message("debug.prompt.notStopped") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            return
        }
        scope.launch {
            val result = frame.executor.withSession<DapEvaluationResult> { with(frame.frame) { evaluate(text, DapEvaluationContext.Repl) } }
            withContext(Dispatchers.EDT) {
                when (result) {
                    is DapEvaluationResult.Success -> console.print(result.variable.value + "\n", ConsoleViewContentType.NORMAL_OUTPUT)
                    is DapEvaluationResult.Error -> console.print(result.errorMessage + "\n", ConsoleViewContentType.ERROR_OUTPUT)
                    else -> Unit
                }
            }
        }
    }

    override fun dispose() = Unit

    /**
     * The session's console with this prompt under it, as the debugger's *Console* tab shows it. Its
     * console is the run's, owned and disposed by the run; the prompt goes with the tab's content.
     */
    inner class UnderConsole(private val output: ExecutionConsole) : ExecutionConsole {

        private val component = OnePixelSplitter(true, OUTPUT_PROPORTION).apply {
            firstComponent = output.component
            secondComponent = console.component
        }

        override fun getComponent(): JComponent = component

        override fun getPreferredFocusableComponent(): JComponent = output.preferredFocusableComponent

        override fun dispose() = Unit
    }

    companion object {
        private const val PROMPT = ">>> "

        /** The prompt's history, kept apart from the basedpython console's. */
        private const val HISTORY_TYPE = "basedpython-debug"

        /** How much of the tab the program's output keeps. */
        private const val OUTPUT_PROPORTION = 0.7f
    }
}
