package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase

/**
 * Live-template macro `byOutPath()` — expands to the `out/<rel>.py` path the current `.by` file
 * transpiles to (relative to the project base, `/`-separated), e.g. `out/pkg/sub/foo.py`.
 */
class ByOutPathMacro : MacroBase("byOutPath", "byOutPath()") {

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext?, quick: Boolean): Result {
        val file = ByMacroSupport.currentFile(context)
        return TextResult(ByMacroSupport.outPath(ByMacroSupport.project(context), file))
    }
}
