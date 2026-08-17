package dev.basedpython.pycharm.inspections.explain

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import dev.basedpython.pycharm.format.ByCleanup
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

        val explanation = file?.let { fromBuff(project, it, code) ?: fromBy(project, it, code) }
        val body = explanation?.documentation?.takeIf { it.isNotBlank() }
        if (body != null) return ByRuleExplanationResult.Found(body)

        return ByRuleExplanationResult.NotFound(
            BasedPythonBundle.message(
                if (file == null) "explainRule.noServer" else "explainRule.noExplanation",
            ),
        )
    }

    private fun fromBuff(project: Project, file: VirtualFile, code: String): ByRuleExplanation? {
        val server = ByCleanup.findServer(project, file) ?: return null
        val params = BuffExplainRuleParams(code)
        return server.explain { (it as BuffServerExtensions).explainRule(params) }
    }

    private fun fromBy(project: Project, file: VirtualFile, code: String): ByRuleExplanation? {
        val server = ByTranspile.findServer(project, file) ?: return null
        val params = ByExplainRuleParams(code)
        return server.explain { (it as ByServerExtensions).explainRule(params) }
    }

    /**
     * A server serves a project, not a file, but a request still needs one to be routed by — and
     * *Explain Rule* can be invoked from a prompt with nothing open. Any source this plugin owns
     * will do, because the answer does not depend on which.
     */
    private fun anyOpenSource(project: Project): VirtualFile? =
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .openFiles
            .firstOrNull { it.extension in setOf("by", "byi", "py", "pyi") }

    private fun LspServer.explain(
        request: (org.eclipse.lsp4j.services.LanguageServer) -> java.util.concurrent.CompletableFuture<ByRuleExplanation?>,
    ): ByRuleExplanation? = sendRequestSync { request(it) }
}
