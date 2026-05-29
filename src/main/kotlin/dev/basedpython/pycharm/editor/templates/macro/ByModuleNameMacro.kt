package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase

/**
 * Live-template macro `byModuleName()` — expands to the dotted basedpython module path of the
 * current `.by` file (relative to its source root), matching the run-config module name.
 *
 * Example: editing `pkg/sub/foo.by` expands to `pkg.sub.foo`.
 */
class ByModuleNameMacro : MacroBase("byModuleName", "byModuleName()") {

    override fun calculateResult(params: Array<Expression>, context: ExpressionContext?, quick: Boolean): Result {
        val file = ByMacroSupport.currentFile(context)
        return TextResult(ByMacroSupport.moduleName(ByMacroSupport.project(context), file))
    }
}
