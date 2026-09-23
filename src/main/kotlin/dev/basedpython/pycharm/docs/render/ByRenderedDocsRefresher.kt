package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import com.intellij.util.FileContentUtilCore
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByOpenedDocuments
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
 * ## the signal
 *
 * This used to listen to `LspClientManagerListener.fileOpened`, which fires exactly when the client
 * has told a server about a file — the precise moment the earlier answer became wrong. That
 * interface is `@ApiStatus.Internal`. For a while it was replaced by a re-check 700ms after a file
 * opened, which was a guess at how long the platform takes to send its `didOpen`; now it is
 * [ByOpenedDocuments], which hears the platform build that `didOpen` through the descriptor's public
 * `getLanguageId` — the same moment the internal listener marked. See docs/internal-api.md.
 *
 * That covers both occasions that matter: a file opened while a server runs, and every file already
 * on screen when a server starts, which the platform opens on the new server one by one. The
 * rendering pass asks nothing before then ([ByDocstringSpans] waits for the same news), so the first
 * look at a freshly opened file records no answer rather than a refused one, and the look this makes
 * once the server has the file is the one that counts.
 *
 * It also keeps the reparse off every file no server was told about. [FileContentUtilCore.reparseFiles]
 * is a write action that fires PSI change events, and the daemon answers those by discarding
 * whatever it is computing: *"PSI/document/model changes are not allowed during highlighting,
 * because it leads to the daemon unnecessary restarts."* When the re-check was armed for every file
 * that opened, server or no server, that surfaced as tests elsewhere in the module failing perhaps
 * one run in three.
 *
 * ## what re-runs the pass
 *
 * [FileContentUtilCore.reparseFiles], where this used to call `DocRenderManager.resetEditorToDefaultState`
 * — also internal, its whole package being marked so. A reparse bumps the modification count the
 * pass's own skip is keyed on, which is the thing that had to happen; it is also narrower than what
 * it replaces, since `resetEditorToDefaultState` returned manually toggled blocks to their default
 * and this leaves them alone.
 *
 * It only acts when the server has not answered for the file as it is now. An empty answer is an
 * answer — a file with no docstrings — and reparsing it would buy a daemon restart for nothing,
 * which is what every docstring-less file used to pay after it opened, because "no docstrings" and
 * "not asked yet" read the same.
 *
 * ## a server became ready
 *
 * Every answer held so far — the spans in [ByDocstringSpanCache], the markdown in [ByRenderedDocs] —
 * came from no server or from the previous one, and a restart is how a rebuilt `by` or a changed
 * configuration arrives. So both are dropped here, whichever route restarted the server (the action,
 * a settings change, crash recovery). Every open `.by` file is looked at again as the platform opens
 * it on the new server.
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
                    if (serverName == BY_SERVER) forgetAnswers()
                }
            },
        )

        connection.subscribe(
            ByOpenedDocuments.Listener.TOPIC,
            // Through the alarm rather than straight through: this arrives inside the platform's
            // write action, before its `didOpen` has gone out, and a reparse is a write action of its
            // own. Only a file an editor shows has docstrings to render; and if the server answered
            // for it in the meantime, even with nothing, there is nothing to fix.
            ByOpenedDocuments.Listener { file ->
                if (FileEditorManager.getInstance(project).isFileOpen(file)) {
                    alarm.addRequest({ refreshIfStale(file) }, 0)
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
    }
}
