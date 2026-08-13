package dev.basedpython.pycharm.debug

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
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
 */
object ByDebuggerEditorsProvider : XDebuggerEditorsProviderBase() {

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

    private const val FRAGMENT_NAME = "basedpython-expression.by"
}
