package dev.basedpython.pycharm.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.HoverParams

/**
 * The `by` server serving [file], or `null` when there is none — switched off in settings, still
 * starting, or dead.
 *
 * Also `null` on the EDT, where a request is not merely slow but forbidden:
 * [LspClient.sendRequestSync] is `@RequiresBackgroundThread` and asserts. The platform calls into
 * documentation from both threads — the rendering pass and the gutter control's hit test are not
 * the same caller — so refusing here turns a hard failure into the same "no server" the callers
 * already handle, and the background pass that follows gets the real answer.
 */
internal fun runningByServer(project: Project, file: VirtualFile): LspServer? {
    if (ApplicationManager.getApplication().isDispatchThread) return null
    return byServerFor(project, file)
}

/**
 * The same lookup without the thread rule, for deciding whether asking is worth arranging at all.
 *
 * Safe on any thread because it sends nothing: it reads the manager's list of servers already
 * started for this project. A caller on the EDT that gets a server back still cannot make a request
 * on it — it has to hand that to a background thread, which is exactly what
 * [dev.basedpython.pycharm.lsp.inject.ByInjections] does with the answer.
 */
internal fun byServerFor(project: Project, file: VirtualFile): LspServer? =
    LspServerManager.getInstance(project)
        .getServersForProvider(ByLspServerSupportProvider::class.java)
        .firstOrNull { it.state == LspServerState.Running && it.descriptor.isSupportedFile(file) }

/** What `textDocument/hover` had to say, with "nothing to say" kept apart from "nobody to ask". */
sealed interface ByHover {

    /** The server answered with something. */
    data class Markup(val text: String) : ByHover

    /** The server answered, and has nothing to say about this position. */
    data object Nothing : ByHover

    /** No server answered: switched off, still starting, dead, or the request failed. */
    data object NoServer : ByHover
}

/**
 * Asks the `by` server what it knows about an offset.
 *
 * `by`'s LSP surface is the standard one, and hover is the one request on it that carries the type
 * *and* the docstring — which is why more than one feature here goes through it: Type Info shows the
 * first block, rendered documentation shows the rest. Keeping the request in one place keeps the
 * two honest about what the payload is.
 *
 * Threading: [LspServer.sendRequestSync] is background-thread-only and polls
 * `ProgressManager.checkCanceled` while it waits, so callers must already be off the EDT — a write
 * action cancels the wait instead of queueing behind it, and `timeoutMs` bounds a server that has
 * stopped answering.
 */
object ByHoverRequest {

    fun at(file: PsiFile, offset: Int, timeoutMs: Int): ByHover {
        val virtualFile = file.originalFile.virtualFile ?: return ByHover.NoServer
        val document = file.viewProvider.document ?: return ByHover.NoServer
        if (offset < 0 || offset > document.textLength) return ByHover.NoServer
        val server = runningByServer(file.project, virtualFile) ?: return ByHover.NoServer
        ByServerDocuments.ensureOpen(server, file.project, virtualFile)

        val params = HoverParams(server.getDocumentIdentifier(virtualFile), getLsp4jPosition(document, offset))
        val answer = server.askBy("textDocument/hover", timeoutMs) { it.textDocumentService.hover(params) }
        val hover = when (answer) {
            is ByAnswer.Answer -> answer.value
            ByAnswer.None -> return ByHover.Nothing
            ByAnswer.Failed -> return ByHover.NoServer
        }

        // `by` always replies with MarkupContent; the List form is the deprecated MarkedString shape.
        val text = hover.contents?.let { contents ->
            when {
                contents.isRight -> contents.right?.value
                contents.isLeft -> contents.left.orEmpty().joinToString("\n") {
                    if (it.isLeft) it.left else it.right?.value.orEmpty()
                }

                else -> null
            }
        }
        return if (text.isNullOrBlank()) ByHover.Nothing else ByHover.Markup(text)
    }
}
