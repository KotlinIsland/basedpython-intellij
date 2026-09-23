package dev.basedpython.pycharm.inspections.explain

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.BuffExplainRuleParams
import dev.basedpython.pycharm.lsp.ext.BuffServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByExplainRuleParams
import dev.basedpython.pycharm.lsp.ext.ByRuleExplanation
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.transpile.ByTranspile
import dev.basedpython.pycharm.util.BasedPythonBundle

/** The outcome of looking a rule up in whichever tool owns it. */
internal sealed interface ByRuleExplanationResult {
    /** [body] is the tool's markdown explanation, ready to display. */
    data class Found(val body: String) : ByRuleExplanationResult

    /** [message] is the reason, already fit to show the user. */
    data class NotFound(val message: String) : ByRuleExplanationResult
}

/**
 * Looks up the documentation for one diagnostic code.
 *
 * Two tools own two disjoint sets of rules and neither knows the other's, so both get asked:
 * `buff` answers for the linter's codes and declines `redundant-return-annotation`, while `by`
 * answers for the type checker's and declines `F401`. `buff` goes first only because its codes are
 * the more common ask.
 *
 * Both are asked over LSP, of the servers already running for this project. This used to spawn
 * `buff rule <code>` and then `by explain rule <code>` and read their stdout — two processes for
 * what is a table lookup in a server that is already up, each rediscovering the project's
 * configuration by a route the editor does not use.
 *
 * The prose itself comes from the crate that owns the rule, so what shows here and what
 * `buff rule` prints in a terminal are one rendering rather than two that drift.
 */
internal object ByRuleExplainer {

    fun explain(project: Project, code: String, contextFile: VirtualFile? = null): ByRuleExplanationResult {
        val file = contextFile ?: anyOpenSource(project)
            ?: return ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.noServer"))
        return explain(code, buff = ByCleanup.findServer(project, file), by = ByTranspile.findServer(project, file))
    }

    /**
     * Asks [buff], then [by], for [code].
     *
     * Through `askBy`, because each server's "not mine" is an empty answer, and `sendRequestSync`
     * returns the same `null` for an error answer, a timeout and a request never sent. Read as they
     * came, a `buff` that failed looked like one that had never heard of the rule, and a lookup that
     * failed on both sides told the user the rule had no explanation. Now a failure on either side,
     * with no explanation from the other, says that a server did not answer.
     */
    fun explain(code: String, buff: LspClient?, by: LspClient?): ByRuleExplanationResult {
        if (buff == null && by == null) {
            return ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.noServer"))
        }
        val fromBuff = buff?.askBy("buff/explainRule") {
            (it as BuffServerExtensions).explainRule(BuffExplainRuleParams(code))
        }
        found(fromBuff)?.let { return it }
        val fromBy = by?.askBy("by/explainRule") {
            (it as ByServerExtensions).explainRule(ByExplainRuleParams(code))
        }
        found(fromBy)?.let { return it }

        val failed = fromBuff == ByAnswer.Failed || fromBy == ByAnswer.Failed
        return ByRuleExplanationResult.NotFound(
            BasedPythonBundle.message(if (failed) "explainRule.serverDidNotAnswer" else "explainRule.noExplanation"),
        )
    }

    private fun found(answer: ByAnswer<ByRuleExplanation>?): ByRuleExplanationResult.Found? =
        answer?.value?.documentation?.takeIf { it.isNotBlank() }?.let { ByRuleExplanationResult.Found(it) }

    /**
     * A server serves a project, not a file, but a request still needs one to be routed by, and
     * Explain Rule can be invoked from a prompt with nothing open. Any source this plugin owns
     * will do, because the answer does not depend on which.
     */
    private fun anyOpenSource(project: Project): VirtualFile? =
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openFiles
            .firstOrNull { it.extension in setOf("by", "byi", "py", "pyi") }
}
