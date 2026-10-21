package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import java.util.concurrent.CancellationException

/**
 * What asking `by` to check the whole workspace came to: the report and the server it came from,
 * or why there is none.
 *
 * Shared by the two callers that want the whole project's diagnostics at once — *Inspect Code*
 * ([ByDiagnosticsInspection]) and the IDE's MCP server (`mcp.ByMcpToolset`) — so both start `by`
 * the same way and say the same thing when it cannot answer.
 */
internal sealed interface ByWorkspaceCheck {

    class Report(val client: LspClient, val report: WorkspaceDiagnosticReport) : ByWorkspaceCheck

    class Not(val why: String) : ByWorkspaceCheck

    companion object {

        /** The running `by`'s check of the workspace — starting `by` if it is not running — or why there is none. */
        suspend fun check(project: Project): ByWorkspaceCheck {
            val client = when (val start = awaitByServer(project)) {
                is ByServerStart.Running -> start.client
                is ByServerStart.Unavailable -> return Not(BasedPythonBundle.message("inspection.by.noServer", start.why))
            }
            val params = WorkspaceDiagnosticParams(emptyList())
            var refusal: Throwable? = null
            // No timeout: a whole-project check takes as long as the project takes, and the caller can
            // be cancelled. Asked again on `ContentModified`; `by` restarts a check an edit cancels itself.
            val answer = awaitingAgain<WorkspaceDiagnosticReport>("by/checkWorkspace", Long.MAX_VALUE) { sent ->
                try {
                    client.sendRequest { (it as ByServerExtensions).checkWorkspace(params).also(sent) }
                } catch (e: Exception) {
                    if (e !is CancellationException) refusal = e
                    throw e
                }
            }
            return when (answer) {
                is ByAnswer.Answer -> Report(client, answer.value)
                ByAnswer.None -> Not(BasedPythonBundle.message("inspection.by.noAnswer"))
                ByAnswer.Failed -> Not(
                    when {
                        refusal != null && isMethodNotFound(refusal) -> BasedPythonBundle.message("inspection.by.unsupported")
                        refusal != null -> BasedPythonBundle.message("inspection.by.refused", refusal.message.orEmpty())
                        else -> BasedPythonBundle.message("inspection.by.noAnswer")
                    },
                )
            }
        }
    }
}
