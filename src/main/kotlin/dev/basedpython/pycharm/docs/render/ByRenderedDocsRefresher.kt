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
import dev.basedpython.pycharm.lsp.byServerFor

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
 *  - **a file was opened while a server was already running** — the platform's `didOpen` for it is
 *    in flight, and completion is not observable from here, so this looks again shortly afterwards
 *    rather than being told.
 *
 * The second is a delayed re-check where there used to be an event, which is the honest cost of the
 * swap. It is bounded — one look per file, [RECHECK_MS] after it opens — and it is cheap, because
 * what it asks is a cached lookup that has already happened.
 *
 * ## while a server was already running
 *
 * That clause is load-bearing, and for a while it was in this comment and not in the code: the
 * re-check was armed for every file that opened, server or no server. With nobody to have sent a
 * `didOpen` to, the reparse it leads to cannot make a docstring appear — the file has none recorded
 * because none can be fetched, not because the answer arrived early — so it is pure cost, and the
 * cost is not nothing.
 *
 * [FileContentUtilCore.reparseFiles] is a write action that fires PSI change events, and the daemon
 * answers those by discarding whatever it is computing and starting over. Fired from a timer it
 * lands wherever it lands, including in the middle of a highlighting pass, and the platform says so
 * when it catches it: *"PSI/document/model changes are not allowed during highlighting, because it
 * leads to the daemon unnecessary restarts."* In this repository that surfaced as tests elsewhere in
 * the module failing perhaps one run in three — whichever one happened to be highlighting a `.by`
 * file when an unarmed-for-nothing timer went off. Asking whether there is a server first is both
 * the correct condition and the end of that.
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
                    // Everything already open was asked while there was nothing to ask. Through the
                    // alarm rather than straight through, because this arrives on whatever thread
                    // the server's initialisation ran on and a reparse takes a write action.
                    val open = FileEditorManager.getInstance(project).openFiles.toList()
                    alarm.addRequest({ open.forEach { refreshIfStale(project, it) } }, 0)
                }
            },
        )

        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    // Only when there is a server that has been told about this file, or is about to
                    // be. With none, the re-check cannot turn up a docstring and the reparse it
                    // leads to is a daemon restart bought for nothing — see the class docs.
                    if (byServerFor(project, file) == null) return
                    // One look, once the client has had time to send its `didOpen`. If the server
                    // still says nothing, the file has no docstrings and there is nothing to fix.
                    alarm.addRequest({ refreshIfStale(project, file) }, RECHECK_MS)
                }
            },
        )
    }

    /** Re-runs the rendering pass over [file], but only if it is a `.by` file with nothing recorded. */
    private fun refreshIfStale(project: Project, file: VirtualFile) {
        val stale = ReadAction.computeBlocking<Boolean, RuntimeException> {
            if (project.isDisposed || !file.isValid) return@computeBlocking false
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@computeBlocking false
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
