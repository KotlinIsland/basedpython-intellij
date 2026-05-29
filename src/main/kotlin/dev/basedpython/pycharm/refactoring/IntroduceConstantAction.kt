package dev.basedpython.pycharm.refactoring

import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan

/**
 * "Introduce Constant" for `.by` files.
 *
 * Like Extract Variable, but inserts the assignment at the module top (after the leading
 * comment/import header) using an UPPER_CASE name, then replaces the selection with that name.
 */
class IntroduceConstantAction : AbstractExtractionAction() {

    override val commandTitle: String = "Introduce Constant"
    override val namePrompt: String = "Constant name:"

    override fun suggestName(expression: String): String = ExtractionLogic.defaultConstantName(expression)

    override fun buildPlan(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan = ExtractionLogic.planIntroduceConstant(text, selectionStart, selectionEnd, name)
}
