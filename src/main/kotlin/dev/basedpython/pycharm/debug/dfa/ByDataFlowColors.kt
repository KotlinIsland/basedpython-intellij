package dev.basedpython.pycharm.debug.dfa

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * How a data-flow finding is drawn.
 *
 * Both keys fall back to something the platform already means, rather than inventing a look. Code
 * that will not run is exactly what `NOT_USED_ELEMENT_ATTRIBUTES` is for, and a reader already
 * knows what a faded line means — the whole point of drawing this is that it needs no legend.
 */
object ByDataFlowColors {

    /**
     * A branch the program will not take.
     *
     * The same fade the IDE uses for an unused import or an unreachable statement. That it is
     * *this* run's dead code rather than every run's is a distinction the reader gets from the
     * fact that a debugger is stopped, not from a second colour.
     */
    @JvmField
    val WILL_NOT_RUN: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DATA_FLOW_WILL_NOT_RUN",
        CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
    )

    /**
     * A condition whose value is settled.
     *
     * No attributes of its own: the verdict beside it is the information, and tinting the
     * condition as well would be saying the same thing twice in a place where the code still
     * matters. The key exists so a scheme *can* say something about it.
     */
    @JvmField
    val DECIDED_CONDITION: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BASEDPYTHON_DATA_FLOW_DECIDED")
}
