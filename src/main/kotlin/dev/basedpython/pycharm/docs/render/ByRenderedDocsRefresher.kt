package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import com.intellij.util.FileContentUtilCore
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener

/**
 * Renders a file's docstrings once `by` is actually able to say where they are.
 *
 * The rendering pass and the language server disagree about when a file is ready, and the pass
 * loses. `by` answers no document request for a file it has not been sent `textDocument/didOpen`
 * for — *"Document … is not open in the session"* — and the client sends that asynchronously, off
 * the event that opened the file. The pass, meanwhile, runs the moment the editor appears. So the
 * first look at a freshly opened file finds no docstrings, and it is not because there are none.
 *
 * On its own that would be permanent. `DocRenderPassFactory` skips the pass entirely while the file's
 * modification count is unchanged, so the empty answer computed a moment too early is what the file
 * keeps until an edit — and a stub in a library is never edited. A feature that only worked after
 * you typed a character is what this fixes.
 *
 * ## the signal, and why it is two signals rather than one
 *
 * This used to listen to `LspClientManagerListener.fileOpened`, which fires exactly when the client
 * has told a server about a file — the precise moment the earlier answer became wrong. That
 * interface is `@ApiStatus.Internal`, and the public [com.intellij.platform.lsp.api.LspServerListener]
 * that replaced it elsewhere in this plugin has no per-file callback at all. See docs/internal-api.md.
 *
 * So the one exact signal is replaced by the two occasions it actually mattered:
 *
 *  - **a server became ready** ([ByLspLifecycleListener.serverInitialized]) — every `.by` file
 *    already on screen was looked at while there was nothing to ask, and every one of those answers
 *    is now wrong;
 *  - **a file was opened** while a server was already running — the platform's `didOpen` for it is
 *    in flight, and completion is not observable from here, so this looks again shortly afterwards
 *    rather than being told.
 *
 * The second is a delayed re-check where there used to be an event, which is the honest cost of the
 * swap. It is bounded — one look per file, [RECHECK_MS] after it opens — and it is cheap, because
 * what it asks is a cached lookup that has already happened.
 *
 * ## what re-runs the pass
 *
 * [FileContentUtilCore.reparseFiles], where this used to call `DocRenderManager.resetEditorToDefaultState`
 * — also internal, its whole package being marked so. A reparse bumps the modification count the
 * pass's own skip is keyed on, which is the thing that had to happen; it is also narrower than what
 * it replaces, since `resetEditorToDefaultState` returned manually toggled blocks to their default
 * and this leaves them alone.
 *
 * Either way it only acts when the file has no docstrings recorded, so a file that rendered
 * correctly is never disturbed.
 */
internal class ByRenderedDocsRefresher : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect()
        // Parented to the connection, so both go when the project does.
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, connection)

        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    // Everything already open was asked while there was nothing to ask.
                    FileEditorManager.getInstance(project).openFiles.forEach { refreshIfStale(project, it) }
                }
            },
        )

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    // One look, once the client has had time to send its `didOpen`. If the server
                    // still says nothing, the file has no docstrings and there is nothing to fix.
                    alarm.addRequest({ refreshIfStale(project, file) }, RECHECK_MS)
                }
            },
        )
    }

    /** Re-runs the rendering pass over [file], but only if it is a `.by` file with nothing recorded. */
    private fun refreshIfStale(project: Project, file: VirtualFile) {
        val stale = ReadAction.compute<Boolean, RuntimeException> {
            if (project.isDisposed || !file.isValid) return@compute false
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute false
            psiFile is BasedPythonFile && ByDocstringSpans.cached(psiFile).isEmpty()
        }
        if (!stale) return
        FileContentUtilCore.reparseFiles(listOf(file))
    }

    private companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] publishes under. */
        const val BY_SERVER = "by"

        /**
         * How long after a file opens to look again.
         *
         * Long enough for the client's `didOpen` and the server's first parse, short enough that a
         * docstring does not visibly arrive late. It is a re-check rather than a poll: one request
         * per opened file, and it asks a cache.
         */
        const val RECHECK_MS = 700
    }
}
