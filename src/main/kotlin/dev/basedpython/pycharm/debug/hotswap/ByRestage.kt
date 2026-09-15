package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByRestage as ByRestageAnswer
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTranspileForBuildParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Asking `by` what an edit's slots in a running build's tree should now contain.
 *
 * ## why the server and not the binary
 *
 * Measured, on a 97-file project at `by` HEAD: a full `by build` is 24.9 seconds, of which
 * `by check` is 8.5. A subprocess would pay project discovery and that whole check on every press
 * of the button. The language server has already paid both — it is holding the project database,
 * warm, because it has been answering diagnostics for this project all along — so what is left is
 * the edited files' emit.
 *
 * It is also the same binary. The server is started as `by server` from the configured `by`, so the
 * transpiler that re-stages is the transpiler that built the tree — and where it is *not*, because
 * a user pointed the two at different builds, `_by_build.json` in the tree records which `by` wrote
 * it and the server refuses rather than emitting bytes the build would not have.
 *
 * ## one request for the whole edit
 *
 * The tree has one `_by_sourcemap.py` for every module in it. Asked a file at a time, `by` could
 * only answer each with the tree's map plus that one file's entry, and writing those answers in turn
 * kept the last file's line table and silently dropped every other — after which bpd re-armed every
 * `.by` breakpoint against a table describing code the tree no longer held. So the whole set is
 * sent together and comes back with one map that carries all of it.
 *
 * ## what comes back
 *
 * Bytes and destinations. Nothing is written by the server, because writing has to be undoable
 * together with the debugger request that follows it — see [ByBuildTree].
 */
internal object ByRestage {

    /** What asking produced. */
    sealed interface Asked {
        /** `by` answered, with bytes or with its reasons. */
        data class Answered(val answer: ByRestageAnswer) : Asked

        /** No server running for these files, or the request failed or timed out. */
        data object NoAnswer : Asked

        /**
         * The files are served by different `by` servers. A build is made of one project, so there
         * is no one server to ask about all of it — and asking each about its own files would be the
         * map-per-answer this request exists to prevent.
         */
        data object SeveralServers : Asked
    }

    /**
     * What every one of `files`' slots in `buildDirectory` should now hold.
     *
     * [Asked.NoAnswer] is deliberately not merged with a refusal, which means the server looked and
     * would not — a user can act on the second and only a maintainer can act on the first.
     */
    fun ask(project: Project, files: List<VirtualFile>, buildDirectory: String): Asked {
        val servers = files.map { byServerFor(project, it) }.distinct()
        if (null in servers) return Asked.NoAnswer
        val server = servers.singleOrNull() ?: return Asked.SeveralServers

        val params = ByTranspileForBuildParams(
            textDocuments = files.map { TextDocumentIdentifier(server.getDocumentIdentifier(it).uri) },
            buildDirectory = buildDirectory,
        )
        val answer = server.askBy("by/transpileForBuild", TIMEOUT_MS) {
            (it as ByServerExtensions).transpileForBuild(params)
        }.value
        return answer?.let { Asked.Answered(it) } ?: Asked.NoAnswer
    }

    /**
     * Longer than an editor request and shorter than a build.
     *
     * One file's emit off a warm database is about 165ms measured, but the database is only warm for
     * what it has already been asked about — the first re-stage after a cold start pays whatever the
     * project's check costs, and on a large project that was 8.5 seconds. A timeout under that would
     * turn the first press of the button after opening a project into a failure every time.
     */
    private const val TIMEOUT_MS = 30_000
}
