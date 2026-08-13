package dev.basedpython.pycharm.debug

import com.intellij.openapi.fileTypes.FileType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
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
 * The base class does the rest: [createDocument] puts the text in a light file of this type, which
 * makes it a basedpython PSI file, which is all the highlighter and the LSP need.
 */
object ByDebuggerEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE
}
