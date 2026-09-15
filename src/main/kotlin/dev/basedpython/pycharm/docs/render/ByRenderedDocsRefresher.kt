package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
import org.jetbrains.annotations.VisibleForTesting

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
 * On a file that was opened it only acts when the server has not answered for the file as it is now.
 * An empty answer is an answer — a file with no docstrings — and reparsing it would buy a daemon
 * restart for nothing, which is what every docstring-less file used to pay [RECHECK_MS] after it
 * opened, because "no docstrings" and "not asked yet" read the same.
 *
 * ## a server became ready
 *
 * Every answer held so far — the spans in [ByDocstringSpanCache], the markdown in [ByRenderedDocs] —
 * came from no server or from the previous one, and a restart is how a rebuilt `by` or a changed
 * configuration arrives. So both are dropped here, whichever route restarted the server (the action,
 * a settings change, crash recovery), and every open `.by` file is looked at again.
 *
 * ## lifetime
 *
 * A project service, so the connection and the alarm are disposed with the project or with the
 * plugin, whichever goes first; [Activity] only makes sure it exists once a project opens.
 */
@Service(Service.Level.PROJECT)
internal class ByRenderedDocsRefresher(private val project: Project) : Disposable {

    internal class Activity : ProjectActivity {
        override suspend fun execute(project: Project) {
            project.service<ByRenderedDocsRefresher>()
        }
    }

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        val connection = project.messageBus.connect(this)

        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    forgetAnswers()
                    // Everything already open was asked while there was nothing to ask, or asked of
                    // a server that is gone. Through the alarm rather than straight through, because
                    // this arrives on whatever thread the server's initialisation ran on and a
                    // reparse takes a write action.
                    val open = FileEditorManager.getInstance(project).openFiles.toList()
                    alarm.addRequest({ open.forEach { refreshIfStale(it) } }, 0)
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
                    // answered in the meantime, even with nothing, there is nothing to fix.
                    alarm.addRequest({ refreshIfStale(file) }, RECHECK_MS)
                }
            },
        )
    }

    /** Drops what the previous server said, so that nothing it said outlives it. */
    private fun forgetAnswers() {
        project.service<ByDocstringSpanCache>().clear()
        ByRenderedDocs.clearCache()
    }

    /** Re-runs the rendering pass over [file], but only if it is a `.by` file with no answer recorded. */
    private fun refreshIfStale(file: VirtualFile) {
        if (isStale(file)) FileContentUtilCore.reparseFiles(listOf(file))
    }

    /** Whether [file] is a `.by` file the server has not answered for, as the file is now. */
    @VisibleForTesting
    fun isStale(file: VirtualFile): Boolean = ReadAction.computeBlocking<Boolean, RuntimeException> {
        if (project.isDisposed || !file.isValid) return@computeBlocking false
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@computeBlocking false
        psiFile is BasedPythonFile && ByDocstringSpans.recorded(psiFile) == null
    }

    override fun dispose() {}

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
