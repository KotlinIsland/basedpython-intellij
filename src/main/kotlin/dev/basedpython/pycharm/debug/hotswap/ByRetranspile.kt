package dev.basedpython.pycharm.debug.hotswap

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByRetranspileParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Asking `by` to transpile an edit's `.by` modules again into the run directory of a program
 * `by run --in-place` runs, `by/retranspile` — what [ByRestage] is to a staged run.
 *
 * A run in place has no tree to rewrite: the program imports each `.by` module from its transpile in
 * `by`'s run cache, and every python it starts reads the run's map, which is never rewritten. So the
 * plugin writes nothing at all. `by` writes the new transpile into the run directory, where nothing
 * the program runs reads it until bpd is told to, and answers with what to tell it: the transpile's
 * place and the module's entry of the map's tables, checked with the gate `by run` uses, in the
 * configuration and environment the run recorded — so the answer is the one `by retranspile` would
 * print, whatever environment the server was started in.
 *
 * Asked of the language server rather than of `by retranspile` on the command line for the reason
 * [ByRestage] gives: it is the project's own `by`, already running, and the plugin starts nothing.
 */
internal object ByRetranspile {

    /** What asking produced. */
    sealed interface Asked {
        /** `by` answered, one entry for each module in the order asked, as it wrote them. */
        data class Answered(val answers: List<JsonObject>) : Asked

        /** No server running for these files, or the request failed or timed out. */
        data object NoAnswer : Asked

        /** The files are served by different `by` servers, and a running program is one project's. */
        data object SeveralServers : Asked
    }

    /** `by`'s answer for every one of [modules], saved `.by` files, in the run out of [runDirectory]. */
    fun ask(project: Project, modules: List<VirtualFile>, runDirectory: String): Asked {
        val servers = modules.map { byServerFor(project, it) }.distinct()
        if (null in servers) return Asked.NoAnswer
        val server = servers.singleOrNull() ?: return Asked.SeveralServers
        return ask(server, modules, runDirectory)
    }

    /**
     * [ask] of one [server] that serves every one of [modules]. Background threads only.
     *
     * `by` reads each module from disk, as the program would load it, and not from its database, so
     * it needs no word of the edit beyond the bytes being there: each file's pending write is flushed
     * first, which the platform does for anyone who asks for the file's [java.nio.file.Path] — from 263
     * a saved document reaches the disk after `saveDocument` returns ([ByRestage.ask] has the
     * measurement), and a module transpiled from the bytes before the save would be the code already
     * running.
     */
    internal fun ask(server: LspClient, modules: List<VirtualFile>, runDirectory: String): Asked {
        for (module in modules) module.fileSystem.getNioPath(module)
        val params = ByRetranspileParams(
            textDocuments = modules.map { TextDocumentIdentifier(server.descriptor.getFileUri(it)) },
            runDirectory = runDirectory,
        )
        val answers = server.askBy("by/retranspile", TIMEOUT_MS) {
            (it as ByServerExtensions).retranspile(params)
        }.value
        return answers?.let { Asked.Answered(it) } ?: Asked.NoAnswer
    }

    /**
     * As [ByRestage]'s: a module transpiled again pays the run's check of it, out of the project as it
     * is on disk, in databases of its own rather than the server's warm one.
     */
    private const val TIMEOUT_MS = 30_000
}
