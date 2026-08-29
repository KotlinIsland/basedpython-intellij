package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByRestaged
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTranspileForBuildParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Asking `by` what one file's slot in a running build's tree should now contain.
 *
 * ## why the server and not the binary
 *
 * Measured, on a 97-file project at `by` HEAD: a full `by build` is 24.9 seconds, of which
 * `by check` is 8.5. A subprocess would pay project discovery and that whole check on every press
 * of the button. The language server has already paid both — it is holding the project database,
 * warm, because it has been answering diagnostics for this project all along — so what is left is
 * one file's emit.
 *
 * It is also the same binary. The server is started as `by server` from the configured `by`, so the
 * transpiler that re-stages is the transpiler that built the tree — and where it is *not*, because
 * a user pointed the two at different builds, `_by_build.json` in the tree records which `by` wrote
 * it and the server refuses rather than emitting bytes the build would not have.
 *
 * ## what comes back
 *
 * Bytes and a destination. Nothing is written by the server, because writing has to be undoable
 * together with the debugger request that follows it — see [ByBuildTree].
 */
internal object ByRestage {

    /**
     * What `file`'s slot in `buildDirectory` should now hold, or null when nothing answered.
     *
     * Null is "there was no answer": no server running for this file, or the request failed. It is
     * deliberately not merged with a [ByRestaged.refused] answer, which means the server looked and
     * would not — a user can act on the second and only a maintainer can act on the first.
     */
    fun ask(project: Project, file: VirtualFile, buildDirectory: String): ByRestaged? {
        val server = LspServerManager.getInstance(project)
            .getServersForProvider(ByLspServerSupportProvider::class.java)
            .firstOrNull {
                it.state == LspServerState.Running && it.descriptor.isSupportedFile(file)
            }
            ?: return null

        val params = ByTranspileForBuildParams(
            textDocument = TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
            buildDirectory = buildDirectory,
        )
        return server.askBy("by/transpileForBuild", TIMEOUT_MS) {
            (it as ByServerExtensions).transpileForBuild(params)
        }.value
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
