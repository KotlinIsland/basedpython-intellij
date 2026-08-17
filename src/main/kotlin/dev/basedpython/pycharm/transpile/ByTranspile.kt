package dev.basedpython.pycharm.transpile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTranspileParams
import dev.basedpython.pycharm.util.BasedPythonBundle

private val LOG = Logger.getInstance(ByTranspile::class.java)

/** What the server answered. */
sealed interface ByTranspileResult {
    /** The generated source. */
    data class Generated(val source: String) : ByTranspileResult

    /** The server looked and the source does not lower yet; [message] is why. */
    data class Failed(val message: String) : ByTranspileResult

    /** No server was running for this file, or it declined to answer. */
    data class Unavailable(val message: String) : ByTranspileResult
}

/**
 * Transpiles a document by asking the running `by` server.
 *
 * Not `by transpile <path>` in a subprocess, which is what every caller here used to do. The two
 * differ in what they read: a subprocess reads the file, and an editor's copy of a file is the
 * buffer — so transpiling a document with unsaved edits showed the last saved version of it. That
 * was wrong and it was quiet, which is the worse half.
 *
 * The server is also the better answer even for a saved file. It has the project's configuration
 * resolved and its modules indexed, so it transpiles with cross-module types available; a
 * subprocess rediscovers all of that per call, by a different route, and can disagree with the
 * diagnostics in the same window.
 */
object ByTranspile {

    /** The python [file] lowers to. */
    fun toPython(project: Project, file: VirtualFile): ByTranspileResult =
        request(project, file, reverse = false)

    /**
     * The python [snippet] lowers to, checked on its own.
     *
     * [file] says which document the fragment came from, which is what routes the request to a
     * server; the fragment has no module of its own for cross-module types to resolve against.
     */
    fun snippetToPython(project: Project, file: VirtualFile, snippet: String): ByTranspileResult =
        request(project, file, reverse = false, snippet = snippet)

    /** The basedpython [file] reverses into. */
    fun toBasedPython(project: Project, file: VirtualFile): ByTranspileResult =
        request(project, file, reverse = true)

    /**
     * [toPython] or [toBasedPython], with a failure reported to the user and `null` returned.
     *
     * The shape almost every caller wants: an action that has nothing to show has to say why, and
     * the two reasons — no server, or source that does not lower — read the same to whoever asked.
     */
    fun sourceOrNotify(
        project: Project,
        file: VirtualFile,
        reverse: Boolean = false,
        failureTitle: String,
    ): String? {
        val result = if (reverse) toBasedPython(project, file) else toPython(project, file)
        return report(project, result, failureTitle)
    }

    private fun report(
        project: Project,
        result: ByTranspileResult,
        failureTitle: String,
    ): String? = when (result) {
        is ByTranspileResult.Generated -> result.source
        // Both failures read the same to whoever asked: there is nothing to show, and this is why.
        is ByTranspileResult.Failed -> null.also {
            ByCli.notifyError(project, failureTitle, result.message)
        }
        is ByTranspileResult.Unavailable -> null.also {
            ByCli.notifyError(project, failureTitle, result.message)
        }
    }

    /** [snippetToPython], with a failure reported to the user and `null` returned. */
    fun sourceOrNotifySnippet(
        project: Project,
        file: VirtualFile,
        snippet: String,
        failureTitle: String,
    ): String? = report(project, snippetToPython(project, file, snippet), failureTitle)

    /** The `by` server serving [file], if one is running. */
    fun findServer(project: Project, file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.descriptor.isSupportedFile(file) }

    private fun request(
        project: Project,
        file: VirtualFile,
        reverse: Boolean,
        snippet: String? = null,
    ): ByTranspileResult {
        val server = findServer(project, file)
            ?: return ByTranspileResult.Unavailable(
                BasedPythonBundle.message("transpile.serverNotRunning"),
            )

        val params = ByTranspileParams(server.getDocumentIdentifier(file), reverse, snippet)
        val response = server.sendRequestSync { (it as ByServerExtensions).transpile(params) }
            ?: return ByTranspileResult.Unavailable(
                BasedPythonBundle.message("transpile.serverDidNotAnswer"),
            ).also { LOG.debug("`by` did not answer by/transpile for ${file.path}") }

        response.source?.let { return ByTranspileResult.Generated(it) }
        return ByTranspileResult.Failed(
            response.error ?: BasedPythonBundle.message("transpile.serverDidNotAnswer"),
        )
    }
}
