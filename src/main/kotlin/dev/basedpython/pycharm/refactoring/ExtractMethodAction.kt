package dev.basedpython.pycharm.refactoring

import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * "Extract Method" for `.by` files.
 *
 * Takes the editor selection (one or more whole statements/lines), moves the selected lines into a
 * freshly-generated zero-argument `def <name>():` inserted directly above the enclosing function
 * (or at module level if the selection is not inside any function), and replaces the original
 * lines with a `<name>()` call.
 *
 * This is a pragmatic, indentation-driven transformation — there is no data-flow analysis, so the
 * generated function takes no parameters and returns nothing. See [ExtractMethodLogic] for the full
 * set of limitations. The action stays thin: all of the planning lives in the pure logic and the
 * base [AbstractExtractionAction] applies the resulting plan under a write command.
 */
class ExtractMethodAction : AbstractExtractionAction() {

    override val commandTitle: String get() = BasedPythonBundle.message("refactoring.extractMethod.title")
    override val namePrompt: String get() = BasedPythonBundle.message("refactoring.extractMethod.prompt")

    override fun suggestName(expression: String): String = ExtractMethodLogic.defaultMethodName()

    override fun buildPlan(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan {
        val plan = ExtractMethodLogic.planExtractMethod(text, selectionStart, selectionEnd, name)
        if (!plan.ok) {
            // No-op selection (empty/all-blank): produce a degenerate plan that changes nothing.
            return ExtractionPlan(selectionStart, "", selectionStart, selectionStart, "")
        }
        return plan.toExtractionPlan()
    }
}
