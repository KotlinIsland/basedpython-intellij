@file:Suppress("FunctionName")

package dev.basedpython.pycharm.mcp

import com.google.gson.Gson
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.inspections.explain.ByRuleExplainer
import dev.basedpython.pycharm.inspections.explain.ByRuleExplanationResult
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.diagnostics.ByProjectProblem
import dev.basedpython.pycharm.lsp.diagnostics.ByWorkspaceCheck
import dev.basedpython.pycharm.lsp.ext.ByEntryPointParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTestItemsParams
import dev.basedpython.pycharm.transpile.ByTranspile
import dev.basedpython.pycharm.transpile.ByTranspileResult
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import java.nio.file.Path

/**
 * What `by` knows about a basedpython project, offered to an agent through the IDE's MCP server.
 *
 * Every tool here is a request the plugin already makes of the running `by` for a feature of its
 * own — the transpiled-python view, *Explain Rule*, the test gutter, *Inspect Code*, the run gutter
 * — asked the same way ([awaitBy], [ByTranspile], [ByRuleExplainer], [ByWorkspaceCheck]) and handed
 * back as `by` answered it. An agent working on a `.by` project otherwise has only the CLI, which
 * reads files rather than the editor's buffers and rediscovers the project per call; these answer
 * from the server the editor uses, with unsaved edits included.
 *
 * Nothing is decided here about what the code means: each answer is `by`'s, and a structured one
 * goes back as the JSON `by` sent. A tool that cannot ask — no server, or a `by` that does not know
 * the request — fails with the reason, as the IDE's own tools do.
 *
 * Loaded only with the MCP Server plugin (`basedpython-mcp.xml`), and holds no state, so the plugin
 * unloads with it present.
 */
internal class ByMcpToolset : McpToolset {

    override fun displayName(): String = BasedPythonBundle.message("mcp.toolset.name")

    override fun displayDescription(): String = BasedPythonBundle.message("mcp.toolset.description")

    @McpTool(name = "basedpython_transpile")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Transpiles a basedpython (.by) file to the Python it compiles to, as the running `by` language server does
        |for the editor: from the editor's current text of the file, unsaved changes included. With reverse = true,
        |converts a Python (.py) file to basedpython instead. Returns the generated source.
        """,
    )
    suspend fun basedpython_transpile(
        @McpDescription("Project-relative path of the .by file (or of the .py file, with reverse = true).")
        file_path: String,
        @McpDescription("Convert Python to basedpython instead of basedpython to Python.")
        reverse: Boolean = false,
    ): String {
        val project = currentCoroutineContext().project
        val file = fileAt(project, file_path)
        runningBy(project)
        val result = withContext(Dispatchers.IO) {
            if (reverse) ByTranspile.toBasedPython(project, file) else ByTranspile.toPython(project, file)
        }
        return when (result) {
            is ByTranspileResult.Generated -> result.source
            is ByTranspileResult.Failed -> mcpFail(result.message)
            is ByTranspileResult.Unavailable -> mcpFail(result.message)
        }
    }

    @McpTool(name = "basedpython_explain_rule")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Explains a diagnostic rule of the basedpython toolchain: a type-checker rule of `by` (such as
        |`invalid-assignment`) or a lint rule of `buff` (such as `F401`). Returns the rule's documentation as markdown.
        """,
    )
    suspend fun basedpython_explain_rule(
        @McpDescription("The rule's name or code, as a diagnostic reports it.")
        rule: String,
    ): String {
        val project = currentCoroutineContext().project
        val by = runningBy(project)
        val buff = LspClientManager.getInstance(project)
            .getClients(BuffLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }
        val result = withContext(Dispatchers.IO) { ByRuleExplainer.explain(rule.trim(), buff = buff, by = by) }
        return when (result) {
            is ByRuleExplanationResult.Found -> result.body
            is ByRuleExplanationResult.NotFound -> mcpFail(result.message)
        }
    }

    @McpTool(name = "basedpython_test_items")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Lists the tests pytest collects from basedpython and Python test files, as `by` reads them: each test and
        |test class with its pytest node id (`id`), its kind, and its range (0-based lines). For one file when
        |file_path is given, otherwise for every test file of the project. Returns `by`'s answer as JSON.
        """,
    )
    suspend fun basedpython_test_items(
        @McpDescription("Project-relative path of one test file; empty for the whole project.")
        file_path: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val file = file_path.takeIf { it.isNotBlank() }?.let { fileAt(project, it) }
        val client = runningBy(project)
        val params = ByTestItemsParams(file?.let { client.descriptor.getFileUri(it) })
        val answer = client.awaitBy("by/testItems") { (it as ByServerExtensions).testItems(params) }
        return when (answer) {
            is ByAnswer.Answer -> json(answer.value)
            ByAnswer.None -> json(mapOf("files" to emptyList<Any>()))
            ByAnswer.Failed -> mcpFail(BasedPythonBundle.message("mcp.by.noAnswer", "by/testItems"))
        }
    }

    @McpTool(name = "basedpython_check_workspace")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Type-checks the whole basedpython project with `by`, as Code | Inspect Code does, and returns every error,
        |warning and weak warning, each with its project-relative file, 1-based line and column, severity, rule
        |code and message, as JSON. Open files are checked as the editor has them, unsaved changes included.
        """,
    )
    suspend fun basedpython_check_workspace(): String {
        val project = currentCoroutineContext().project
        val (client, report) = when (val checked = ByWorkspaceCheck.check(project)) {
            is ByWorkspaceCheck.Report -> checked.client to checked.report
            is ByWorkspaceCheck.Not -> mcpFail(checked.why)
        }
        val base = project.projectDirectory
        val problems = report.items.orEmpty()
            .filter { it.isWorkspaceFullDocumentDiagnosticReport }
            .map { it.workspaceFullDocumentDiagnosticReport }
            .flatMap { full ->
                val path = client.descriptor.findFileByUri(full.uri)?.toNioPathOrNull()
                    ?.let { base.relativizeIfPossible(it) } ?: full.uri
                full.items.orEmpty().mapNotNull { diagnostic ->
                    val severity = ByProjectProblem.severityOf(diagnostic) ?: return@mapNotNull null
                    linkedMapOf(
                        "file" to path,
                        "line" to diagnostic.range.start.line + 1,
                        "column" to diagnostic.range.start.character + 1,
                        "severity" to severity.name,
                        "code" to ByProjectProblem.codeOf(diagnostic),
                        "message" to ByProjectProblem.textOf(diagnostic),
                    )
                }
            }
        return json(mapOf("problems" to problems))
    }

    @McpTool(name = "basedpython_entry_point")
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |How a basedpython module runs, as `by run` would run it: its `main` function (0-based line, parameters)
        |and the command line `by` generates from them, and any `if __name__ == "__main__":` guards. Returns `by`'s
        |answer as JSON; an empty answer means no project of `by` holds the file.
        """,
    )
    suspend fun basedpython_entry_point(
        @McpDescription("Project-relative path of the .by or .py module.")
        file_path: String,
    ): String {
        val project = currentCoroutineContext().project
        val file = fileAt(project, file_path)
        val client = runningBy(project)
        val params = ByEntryPointParams(client.descriptor.getFileUri(file))
        val answer = client.awaitBy("by/entryPoint") { (it as ByServerExtensions).entryPoint(params) }
        return when (answer) {
            is ByAnswer.Answer -> json(answer.value)
            ByAnswer.None -> json(emptyMap<String, Any>())
            ByAnswer.Failed -> mcpFail(BasedPythonBundle.message("mcp.by.noAnswer", "by/entryPoint"))
        }
    }

    /** The project's `by`, started if it is not running, or the tool's failure saying why not. */
    private suspend fun runningBy(project: Project): LspClient =
        when (val start = awaitByServer(project)) {
            is ByServerStart.Running -> start.client
            is ByServerStart.Unavailable -> mcpFail(BasedPythonBundle.message("mcp.by.noServer", start.why))
        }

    /** The file at [path] in [project], refreshed from disk if the IDE has not seen it yet. */
    private fun fileAt(project: Project, path: String): VirtualFile {
        val nioPath: Path = project.resolveInProject(path)
        val manager = VirtualFileManager.getInstance()
        return manager.findFileByNioPath(nioPath) ?: manager.refreshAndFindFileByNioPath(nioPath)
            ?: mcpFail(BasedPythonBundle.message("mcp.fileNotFound", path))
    }

    /** As `by` sent it: lsp4j's own Gson, so an `Either` reads as the one value it holds. */
    private fun json(value: Any): String = GSON.toJson(value)

    private companion object {
        val GSON: Gson = MessageJsonHandler(emptyMap()).gson
    }
}
