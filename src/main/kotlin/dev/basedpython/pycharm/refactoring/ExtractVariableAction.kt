package dev.basedpython.pycharm.refactoring

import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * "Extract Variable" for `.by` files.
 *
 * Takes the editor selection (an expression), inserts `name = <expr>` on a new line directly
 * above the current statement at the same indentation, and replaces the selection with `name`.
 * Driven by selection + document text (no expression-level PSI required).
 */
class ExtractVariableAction : AbstractExtractionAction() {

    override val commandTitle: String get() = BasedPythonBundle.message("refactoring.extractVariable.title")
    override val namePrompt: String get() = BasedPythonBundle.message("refactoring.extractVariable.prompt")

    override fun suggestName(expression: String): String = ExtractionLogic.defaultVariableName()

    override fun buildPlan(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan = ExtractionLogic.planExtractVariable(text, selectionStart, selectionEnd, name)
}
