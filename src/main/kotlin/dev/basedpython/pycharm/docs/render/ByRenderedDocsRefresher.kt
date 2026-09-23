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
import org.jetbrains.annotations.VisibleForTesting

/**
 * Renders the docstrings of the files on screen once a `by` server is there to say where they are.
 *
 * `DocRenderPassFactory` skips the rendering pass entirely while the file's modification count is
 * unchanged, so a pass that ran while there was no server — the project opening, a restart — found
 * no docstrings, and that is what the file would keep until an edit. A stub in a library is never
 * edited. A feature that only worked after you typed a character is what this fixes.
 *
 * ## the signal
 *
 * A server becoming ready ([ByLspLifecycleListener.serverInitialized]) — the one occasion on which
 * every earlier look was a look with nobody to ask. A file opened while a server runs needs nothing
 * from here: the pass over it asks at once, naming the text it is about ([ByDocstringSpans]), and
 * `by` answers about that text whether or not the platform's `didOpen` for the file has gone out
 * yet. That used to be the hard case. `by` refused a document it had not been opened on, so this
 * had to run the pass again once the platform had opened it — heard through the internal
 * `LspClientManagerListener.fileOpened`, then guessed at 700ms after a file opened, then heard
 * through the descriptor's `getLanguageId`. See docs/internal-api.md.
 *
 * The reparse is kept to the files on screen that have no answer. [FileContentUtilCore.reparseFiles]
 * is a write action that fires PSI change events, and the daemon answers those by discarding
 * whatever it is computing: *"PSI/document/model changes are not allowed during highlighting,
 * because it leads to the daemon unnecessary restarts."* When a re-check was armed for every file
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
