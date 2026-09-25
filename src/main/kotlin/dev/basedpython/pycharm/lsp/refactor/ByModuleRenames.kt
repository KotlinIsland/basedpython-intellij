package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.FileRename
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

/** A file or directory the editor is about to move from [from] to [to]. */
data class ByFileMove(val from: Path, val to: Path)

/** What `by` said about the imports a set of [ByFileMove]s leaves pointing at a name that is gone. */
sealed interface ByImportRewrites {

    /** The edits that keep every import naming a module that exists. */
    data class Edits(val edit: WorkspaceEdit) : ByImportRewrites

    /** The server answered: nothing imports what moves, or nothing that moves is a module. */
    data object NoneNeeded : ByImportRewrites

    /** No `by` is running for the project to ask; [why] says why, in a sentence for the user. */
    data class NoServer(val why: String) : ByImportRewrites

    /** The running `by` does not say it answers `workspace/willRenameFiles`. */
    data object NotSupported : ByImportRewrites

    /** The request failed or timed out. Never the same as [NoneNeeded]. */
    data object Failed : ByImportRewrites
}

/**
 * `workspace/willRenameFiles`: the edits to the project's imports that a rename or a move of modules
 * and packages needs, asked of `by` before anything moves.
 *
 * Which `import` statements name a module is a question only the server can answer: it resolves
 * every import in the project against the search paths the type checker uses, and tells a use of the
 * module apart from a local name that happens to be spelled like it. So every place in the IDE that
 * moves a module asks here — Refactor | Rename on a file or package, Refactor | Move, a drop in the
 * Project view, and the Modules settings page — and none of them works the edits out itself.
 *
 * ### Why before
 *
 * That is what the request is for, and it is also the only moment the question is answerable: the old
 * path still holds the file, so the server can resolve which module it is, while the new path is a
 * path to derive a name from. Afterwards, neither is true.
 *
 * ### The answers kept apart
 *
 * A server that is not running, one that does not advertise the request, and one that failed to
 * answer all leave the imports unknown, and each is its own [ByImportRewrites] rather than an empty
 * edit: a move that goes ahead on one of them breaks every import of what moved, so the caller has
 * to say so rather than carry on as if nothing needed changing.
 */
object ByModuleRenames {

    /**
     * Asks the project's `by` what [moves] cost, without applying anything — waiting for it to finish
     * initializing if it is still starting.
     *
     * Background only, under the caller's progress: the wait and the request both honour its
     * cancellation, so a cancelled caller unwinds instead of hanging on the server. Every caller asks
     * from inside a modal dialog or progress, where a new start cannot finish (see [awaitByServer]),
     * so one is only asked for, and the answer says so.
     */
    @RequiresBackgroundThread
    fun ask(project: Project, moves: List<ByFileMove>): ByImportRewrites {
        val server = when (val start = runBlockingCancellable { awaitByServer(project, waitForNewStart = false) }) {
            is ByServerStart.Running -> start.client
            is ByServerStart.Unavailable -> return ByImportRewrites.NoServer(start.why)
        }
        if (!advertises(server)) return ByImportRewrites.NotSupported
        val renames = moves.filter { it.from != it.to }
        if (renames.isEmpty()) return ByImportRewrites.NoneNeeded

        val params = RenameFilesParams(renames.map { FileRename(uriOf(it.from), uriOf(it.to)) })
        val answer = server.askBy("workspace/willRenameFiles", REQUEST_TIMEOUT_MS) {
            it.workspaceService.willRenameFiles(params)
        }
        return when (answer) {
            is ByAnswer.Answer -> ByImportRewrites.Edits(answer.value)
            ByAnswer.None -> ByImportRewrites.NoneNeeded
            ByAnswer.Failed -> ByImportRewrites.Failed
        }
    }

    /**
     * Why a rename of modules cannot have its imports rewritten, or `null` when it can: the project's
     * `by` says it answers `workspace/willRenameFiles`.
     *
     * Suspends, cancellably, while a `by` that is starting initializes. Asked from the Modules page,
     * which is in the modal Settings dialog, so a `by` that is not running at all is only asked to
     * start, as in [ask].
     */
    suspend fun whyUnsupported(project: Project): String? =
        when (val start = awaitByServer(project, waitForNewStart = false)) {
            is ByServerStart.Unavailable -> start.why
            is ByServerStart.Running ->
                if (advertises(start.client)) null else BasedPythonBundle.message("refactoring.by.renameFiles.unsupported")
        }

    /** The server's own word on whether it handles `workspace/willRenameFiles`. */
    private fun advertises(server: LspClient): Boolean =
        server.initializeResult?.capabilities?.workspace?.fileOperations?.willRename != null

    /**
     * The URI form a path goes out as.
     *
     * Built from the `java.nio` path rather than by string concatenation so that spaces, non-ASCII
     * names and Windows drive letters are encoded the one way both ends already agree on.
     */
    fun uriOf(path: Path): String = path.toUri().toString()

    /**
     * How long the server gets.
     *
     * Longer than an editor request, because this one reads every file in the project rather than
     * one document — and shorter than forever, because a rename waiting on a server that has stopped
     * answering has to end in something the user can act on.
     */
    private const val REQUEST_TIMEOUT_MS = 30_000
}
