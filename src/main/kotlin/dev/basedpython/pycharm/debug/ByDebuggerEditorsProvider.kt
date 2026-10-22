package dev.basedpython.pycharm.debug

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapExpressionSupport
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * The language every expression the debugger asks for is written in.
 *
 * Small, and load-bearing well beyond its size. A breakpoint type with no editors provider gets
 * plain `JTextField`-grade fields wherever the IDE asks for an expression — the breakpoint dialog's
 * *Condition* and *Evaluate and log*, the Evaluate Expression window — with no highlighting, no
 * completion, and no `by` server behind them, because a document with no file type has no language
 * to run any of that on.
 *
 * It is also what decides whether the inter-line log point editor can open at all. The prompt asks
 * the breakpoint for an editors provider (`XLineBreakpointProxy.getEditorsProvider`, after its own
 * per-language extension point declines) and builds an `XDebuggerExpressionEditor` from it, which
 * does not accept null — so before this existed, clicking the gutter gap in a `.by` file in IntelliJ
 * IDEA had nothing to open the inline field with.
 *
 * [XDebuggerEditorsProviderBase] does the rest of the work — documents, PSI, the supported-language
 * list — given a way to turn expression text into a file. `XDebuggerEditorsProvider` itself is not
 * that base: its `createDocument` is a compatibility stub that throws `AbstractMethodError`, so a
 * provider that only answers [getFileType] compiles and then fails the moment any expression field
 * opens.
 *
 * The fragment is an ordinary light `.by` file rather than a code fragment of its own kind.
 * basedpython has no composite PSI here — the parser produces the file node and one leaf per token
 * (`BasedPythonParserDefinition`) — so there is no expression-shaped tree to build, and a file is
 * exactly as much structure as the highlighter and the lexer-driven features want.
 *
 * This one serves the breakpoints, which belong to no session. A running session's fields are
 * [BySessionEditorsProvider]'s.
 */
object ByDebuggerEditorsProvider : ByExpressionEditorsProvider()

/**
 * The editors of one `.by` debug session: the *Evaluate Expression* field, the Variables view's
 * *Evaluate expression or add a watch* field, and the value tree *Quick Evaluate* shows. The
 * debugger takes all of them from the session's process, and the DAP client's process from what
 * [DapExpressionSupport.createEditorsProvider] answers for the session.
 *
 * The same `.by` fragments as the breakpoints' fields, so the expression is highlighted as the code
 * it is. Left to itself the client answers with its own provider, whose fragments are plain text.
 *
 * Each document is marked with the session it was made for ([FIELD_SESSION]), which is how
 * [ByDapCompletionContributor] finds the frame to ask the adapter's `completions` in — what the
 * client does for its own plain-text fields, which it marks with keys of its own that a plugin
 * cannot use.
 */
internal class BySessionEditorsProvider(private val session: XDebugSession) : ByExpressionEditorsProvider() {

    override fun createDocument(
        project: Project,
        expression: XExpression,
        context: PsiElement?,
        mode: EvaluationMode,
    ): Document = super.createDocument(project, expression, context, mode).also(::mark)

    private fun mark(document: Document) = document.putUserData(FIELD_SESSION, session)
}

/** The [DapExpressionSupport] of a `.by` session: its fields are [BySessionEditorsProvider]'s. */
internal object ByExpressionSupport : DapExpressionSupport() {
    override fun createEditorsProvider(session: XDebugSession): XDebuggerEditorsProvider =
        BySessionEditorsProvider(session)
}

/** A `.by` fragment for whatever expression text a field holds. See [ByDebuggerEditorsProvider]. */
abstract class ByExpressionEditorsProvider : XDebuggerEditorsProviderBase() {

    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE

    override fun createExpressionCodeFragment(
        project: Project,
        text: String,
        context: PsiElement?,
        isPhysical: Boolean,
    ): PsiFile = PsiFileFactory.getInstance(project).createFileFromText(
        FRAGMENT_NAME,
        BasedPythonFileType.INSTANCE,
        text,
        LocalTimeCounter.currentTime(),
        isPhysical,
    )

    private companion object {
        const val FRAGMENT_NAME = "basedpython-expression.by"
    }
}
