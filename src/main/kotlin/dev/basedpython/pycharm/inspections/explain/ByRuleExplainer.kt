package dev.basedpython.pycharm.inspections.explain

import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.util.BasedPythonBundle

/** The outcome of looking a rule up in whichever tool owns it. */
internal sealed interface ByRuleExplanation {
    /** [body] is the tool's markdown-ish explanation, ready to display. */
    data class Found(val body: String) : ByRuleExplanation

    /** [message] is the reason, already fit to show the user. */
    data class NotFound(val message: String) : ByRuleExplanation
}

/**
 * Looks up the documentation for one diagnostic code.
 *
 * Two tools own two disjoint sets of rules and neither knows the other's, so both get asked:
 * `buff rule F401` answers for the linter's codes and rejects `redundant-return-annotation`, while
 * `by explain rule redundant-return-annotation` answers for the type checker's and rejects `F401`.
 * `buff` goes first only because its codes are the more common ask.
 *
 * The `by` invocation is **`explain rule <code>`**, not `explain <code>`: `explain` is a command
 * group, and passing a code straight to it fails with `unrecognized subcommand`. That is what it
 * used to do, so every `by`-owned rule fell through to "no explanation".
 */
internal object ByRuleExplainer {

    /** `buff`'s arguments for [code]. */
    fun buffArguments(code: String): List<String> = listOf("rule", code)

    /** `by`'s arguments for [code] — note the `rule` command under the `explain` group. */
    fun byArguments(code: String): List<String> = listOf("explain", "rule", code)

    fun explain(project: Project, code: String): ByRuleExplanation {
        val buff = ByCli.runBuff(project, *buffArguments(code).toTypedArray())
        if (buff != null && buff.exitCode == 0) return ByRuleExplanation.Found(buff.stdout)

        val by = ByCli.run(project, *byArguments(code).toTypedArray())
        if (by != null && by.exitCode == 0) return ByRuleExplanation.Found(by.stdout)

        val fallback = BasedPythonBundle.message("explainRule.noExplanation")
        val message = (buff?.stderr ?: by?.stderr ?: fallback).ifBlank { fallback }
        return ByRuleExplanation.NotFound(message)
    }
}
