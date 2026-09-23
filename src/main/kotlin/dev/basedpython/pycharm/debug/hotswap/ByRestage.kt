package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByRestage as ByRestageAnswer
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTranspileForBuildParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
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
        return ask(project, server, files, buildDirectory)
    }

    /**
     * [ask] of one [server] that serves every one of [files].
     *
     * Each file is made sure of first, as every other request about a document is: a file the
     * platform does not sync — outside the content roots — is one `by` would otherwise be asked to
     * transpile without having been told the text the IDE holds for it. Background threads only.
     *
     * ## the saved bytes are on disk, and `by` is told so, before it is asked
     *
     * The request names files, not text, and `by` transpiles the text its database holds for each.
     * For a file open in an editor that is the editor's text, which the platform sends as it is
     * typed. For every other file it is the file **as `by` last read it from disk**, and `by` reads
     * it again when it hears the file changed: from its own file system watcher, or from a
     * `workspace/didChangeWatchedFiles`.
     *
     * From 263 the platform writes a saved document to disk **after** `saveDocument` returns:
     * `FileDocumentManagerImpl` is an `AsyncFileContentWriteRequestor`, and the local file system
     * queues its writes. Measured in a 263.5153 sandbox, 10 to 44 saves in a hundred were not on
     * disk when it returned, and landed up to 23 ms later. The platform's own
     * `didChangeWatchedFiles` is put together on a pooled thread when the VFS takes the save, and
     * in 53 saves in a hundred the bytes were not on disk yet at that point. So `by` used to
     * re-read the old text and was never told again.
     * `by` now watches the file system itself and asks the platform for nothing, so every save
     * does reach it, about 25 ms after the bytes land. That is too late for this request, which
     * [ByHotSwapProvider.performHotSwap] sends straight after the save, and which `by` answers from
     * whatever it has read by then: the tree's own bytes back for every file, `changed` false
     * everywhere, and "every edited file already was the code the process is running" with bpd
     * never asked.
     *
     * So, in this order:
     *
     *  1. **each file's pending write is flushed**, which the platform does for anyone who asks for
     *     the file's [java.nio.file.Path] — the contract for a caller about to reach the file other
     *     than through the VFS, which is exactly what `by` is. A file with no path on disk has no
     *     slot in a tree and is left for `by` to refuse;
     *  2. **`by` is told every file changed**, so it reads the bytes now on disk before the request
     *     rather than whenever its watcher reports them. Everything here was just saved — the
     *     provider saves before it asks — so this is only ever the truth, and a file `by` already
     *     had the latest of is re-read to the same text, which changes nothing downstream;
     *  3. **then it is asked**, after the notification because the platform's client sends
     *     notifications and requests through one single-threaded executor, in the order they are
     *     submitted.
     *
     * Measured against a `by` that watches for itself, a hundred saves each, asking straight after
     * the save: with both, no answer about the old text; with the flush alone, or the notification
     * alone, 0 or 1; with neither, 5 or 6. Every one of those caught up within 125 ms, when the
     * watcher's report cancelled the transpile and `by` ran it again. That is a race between the
     * watcher and the transpile, and only the two steps together keep the first answer out of it.
     * Before `by` watched, the numbers were 0, 4 with the flush alone, 2 to 5 with the notification
     * alone, and 12 to 25 with neither, and the ones with the flush alone or with neither never
     * caught up.
     */
    internal fun ask(project: Project, server: LspClient, files: List<VirtualFile>, buildDirectory: String): Asked {
        ReadAction.runBlocking<RuntimeException> {
            for (file in files) ByServerDocuments.ensureOpen(server, project, file)
        }
        for (file in files) file.fileSystem.getNioPath(file)
        val changed = DidChangeWatchedFilesParams(
            files.map { FileEvent(server.descriptor.getFileUri(it), FileChangeType.Changed) },
        )
        server.sendNotification { it.workspaceService.didChangeWatchedFiles(changed) }

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
