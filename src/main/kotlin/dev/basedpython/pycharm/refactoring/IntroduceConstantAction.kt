package dev.basedpython.pycharm.refactoring

import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * "Introduce Constant" for `.by` files.
 *
 * Like Extract Variable, but inserts the assignment at the module top (after the leading
 * comment/import header) using an UPPER_CASE name, then replaces the selection with that name.
 */
class IntroduceConstantAction : AbstractExtractionAction() {

    override val commandTitle: String get() = BasedPythonBundle.message("refactoring.introduceConstant.title")
    override val namePrompt: String get() = BasedPythonBundle.message("refactoring.introduceConstant.prompt")

    override fun suggestName(expression: String): String = ExtractionLogic.defaultConstantName(expression)

    override fun buildPlan(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan = ExtractionLogic.planIntroduceConstant(text, selectionStart, selectionEnd, name)
}
