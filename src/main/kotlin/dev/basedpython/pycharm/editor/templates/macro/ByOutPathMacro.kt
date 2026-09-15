package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase

/**
 * Live-template macro `byOutPath()` — expands to the path `by build` writes the current `.by` file
 * to, relative to the project root and `/`-separated, e.g. `build/pkg/sub/foo.py`. See
 * [ByMacroSupport.outPath].
 */
class ByOutPathMacro : MacroBase("byOutPath", "byOutPath()") {

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext?, quick: Boolean): Result {
        val file = ByMacroSupport.currentFile(context)
        return TextResult(ByMacroSupport.outPath(ByMacroSupport.project(context), file))
    }
}
