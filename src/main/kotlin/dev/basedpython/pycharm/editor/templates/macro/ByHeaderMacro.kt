package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase

/**
 * Live-template macro `byHeader()` — expands to a generated file-header comment line, e.g.
 * `# foo — generated 2026-05-29 by morgan`, using the current file name, date, and system user.
 */
class ByHeaderMacro : MacroBase("byHeader", "byHeader()") {

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext?, quick: Boolean): Result {
        val file = ByMacroSupport.currentFile(context)
        return TextResult(ByMacroSupport.header(file))
    }
}
