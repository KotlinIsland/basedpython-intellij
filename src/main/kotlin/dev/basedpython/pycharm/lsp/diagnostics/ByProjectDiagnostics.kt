package dev.basedpython.pycharm.lsp.diagnostics

import com.google.gson.JsonObject
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.isContentModified
import dev.basedpython.pycharm.lsp.startByServer
import dev.basedpython.pycharm.settings.BasedPythonSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PreviousResultId
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

private val LOG = Logger.getInstance(ByProjectDiagnostics::class.java)

/**
 * What `by` finds wrong in every file of the project, open or not, listed in the Problems view's
 * Project Errors tab and kept current as the project changes.
 *
 * The platform's LSP client asks `by` about the files that are open — `textDocument/diagnostic`,
 * one document at a time — and never sends `workspace/diagnostic`, so without this a problem in a
 * file nobody has open was invisible until someone opened it, or ran `by check` in a console.
 *
 * ## Keeping it current
 *
 * `by` long-polls `workspace/diagnostic`: the request carries the result id of every file already
 * listed, and `by` answers it only once something differs — an edit, a change on disk, a server
 * setting — with a full report for each file that changed and an unchanged report for each that did
 * not. So this sends the request again as soon as each answer is applied, and `by` decides when the
 * next answer comes. Nothing here guesses at which edits matter, and nothing is sent while nothing
 * changes. After an edit it costs one pass over the project, which the server has almost entirely
 * memoized: 0.4 s for an 8,664-file project, whose first pass took 7.3 s.
 *
 * ## What is listed
 *
 * The files the IDE counts as the project's content, which leaves out what the project excludes —
 * a `by build` tree most of all, whose generated copy of every source would otherwise list each
 * problem twice. Not hints: see [ByProjectProblem.of].
 *
 * An open file is listed like any other: the editor's own diagnostics for it are in the Current
 * File tab, not in this one; see [ByProjectProblem].
 *
 * ## When
 *
 * Only while [BasedPythonSettings.byProjectDiagnostics] is on, because it is not free: see there.
 * The rows belong to one server process, and go with it. A restarted server starts from nothing and
 * is sent no result ids, so it lists everything afresh rather than being asked to vouch for a list
 * another process made.
 *
 * A project service: its coroutine scope, its listeners and the rows it handed the tab all go with
 * the project or with the plugin. The rows especially — the collector is the platform's, and a row
 * left in it would hold this plugin's classes after it unloads.
 */
@Service(Service.Level.PROJECT)
internal class ByProjectDiagnostics(
    override val project: Project,
    private val scope: CoroutineScope,
) : ProblemsProvider {

    private val lock = Any()

    /** The pass asking `by` for the next change, while there is one. Guarded by [lock]. */
    private var polling: Job? = null

    /** The server [polling] asks. Guarded by [lock]. */
    private var polled: LspClient? = null

    /** What the tab was handed for each file, as the instances it was handed. Guarded by [lock]. */
    private val shown = HashMap<VirtualFile, List<ByProjectProblem>>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ByLspLifecycleListener.TOPIC, object : ByLspLifecycleListener {
            override fun serverInitialized(serverName: String) {
                if (serverName == BY_SERVER) settingChanged()
            }

            override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                if (serverName == BY_SERVER) stop()
            }
        })
    }

    /**
     * Turns [BasedPythonSettings.byProjectDiagnostics] on and follows `by`, starting it if nothing
     * has yet — a project whose files are all closed has no server to ask.
     */
    fun follow() {
        BasedPythonSettings.getInstance(project).byProjectDiagnostics = true
        settingChanged()
        // resolving the binary can mean asking uv, which is not for the EDT
        if (runningServer() == null) scope.launch(Dispatchers.IO) { startByServer(project) }
    }

    /** Starts or stops following `by`, after [BasedPythonSettings.byProjectDiagnostics] changed. */
    fun settingChanged() {
        if (BasedPythonSettings.getInstance(project).byProjectDiagnostics) start() else stop()
    }

    private fun start() {
        // Not running yet: `serverInitialized` comes back here once it is.
        follow(runningServer() ?: return)
    }

    /** Follows [client], unless this already is. */
    internal fun follow(client: LspClient) {
        synchronized(lock) {
            if (polled === client && polling?.isActive == true) return
            polling?.cancel()
            polled = client
            polling = scope.launch(Dispatchers.Default) { poll(client) }
        }
    }

    private fun stop() {
        synchronized(lock) {
            polling?.cancel()
            polling = null
            polled = null
            clearLocked()
        }
    }

    /**
     * Asks `by` for what changed, applies the answer, and asks again, for as long as this pass is
     * not cancelled and the server is there to ask.
     *
     * [known] is every file listed so far with the result id it was listed under — what `by`
     * compares against to answer with only what changed.
     */
    private suspend fun poll(client: LspClient) {
        val known = HashMap<String, String>()
        while (true) {
            coroutineContext.ensureActive()
            val params = WorkspaceDiagnosticParams(known.map { (uri, id) -> PreviousResultId(uri, id) })
            val sent = AtomicReference<Any>()
            val report = try {
                // No timeout: an unanswered request is the ordinary state of a long poll, and it
                // is answered when the project next changes, which may be never.
                client.sendRequest { server -> server.workspaceService.diagnostic(params).also(sent::set) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAskAgain(e)) {
                    LOG.debug("workspace/diagnostic was overtaken by an edit; asking again")
                    continue
                }
                LOG.warn("workspace/diagnostic failed; Project Errors no longer follows `by` until it restarts", e)
                synchronized(lock) { if (coroutineContext[Job]?.isActive != false) clearLocked() }
                return
            }
            if (report == null) {
                // Not sent, because the server stopped: `serverStopped` has taken the rows away. Or
                // answered `null`, which the protocol does not allow and `by` does not do.
                if (sent.get() != null) LOG.warn("workspace/diagnostic was answered with nothing")
                return
            }
            apply(client, report, known)
        }
    }

    /** Updates [known] and the tab from one answer. */
    private suspend fun apply(client: LspClient, report: WorkspaceDiagnosticReport, known: MutableMap<String, String>) {
        val changed = ArrayList<Pair<String, List<Diagnostic>>>()
        for (item in report.items) {
            if (item.isWorkspaceFullDocumentDiagnosticReport) {
                val full = item.workspaceFullDocumentDiagnosticReport
                // A file with nothing left to report comes back once with no result id, and is not
                // asked about again until it has something.
                val id = full.resultId
                if (id == null) known.remove(full.uri) else known[full.uri] = id
                changed += full.uri to full.items.orEmpty()
            } else {
                val unchanged = item.workspaceUnchangedDocumentDiagnosticReport
                known[unchanged.uri] = unchanged.resultId
            }
        }
        if (changed.isEmpty()) return

        val descriptor = client.descriptor
        val rows = readAction {
            val index = ProjectFileIndex.getInstance(project)
            changed.mapNotNull { (uri, diagnostics) ->
                val file = descriptor.findFileByUri(uri) ?: return@mapNotNull null
                if (!index.isInContent(file)) return@mapNotNull null
                file to diagnostics.mapNotNull { ByProjectProblem.of(this@ByProjectDiagnostics, file, it) }
            }
        }
        synchronized(lock) {
            // A pass cancelled while it read must not put back rows its server took with it.
            coroutineContext.ensureActive()
            for ((file, problems) in rows) replaceLocked(file, problems)
        }
    }

    private fun replaceLocked(file: VirtualFile, problems: List<ByProjectProblem>) {
        val collector = ProblemsCollector.getInstance(project)
        shown.remove(file)?.forEach(collector::problemDisappeared)
        if (problems.isEmpty()) return
        shown[file] = problems
        problems.forEach(collector::problemAppeared)
    }

    private fun clearLocked() {
        if (shown.isEmpty()) return
        val collector = ProblemsCollector.getInstance(project)
        for (problems in shown.values) problems.forEach(collector::problemDisappeared)
        shown.clear()
    }

    /** The rows the tab holds now, for tests. */
    internal fun shownFor(file: VirtualFile): List<ByProjectProblem> = synchronized(lock) { shown[file].orEmpty() }

    private fun runningServer(): LspClient? =
        LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    override fun dispose() {
        synchronized(lock) {
            polling?.cancel()
            polling = null
            clearLocked()
        }
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] publishes under. */
        private const val BY_SERVER = "by"

        fun getInstance(project: Project): ByProjectDiagnostics = project.service()

        /**
         * Whether [error] is `by` saying to send the same request again.
         *
         * `ContentModified` is the answer to an edit landing mid-request; `ServerCancelled` with
         * `retriggerRequest` is what `by` answers when an edit cancels a check it had started. Both
         * say the answer would be stale, not that there is none.
         */
        internal fun isAskAgain(error: Throwable): Boolean =
            isContentModified(error) || generateSequence(error) { it.cause }.any {
                it is ResponseErrorException &&
                    it.responseError.code == ResponseErrorCode.ServerCancelled.value &&
                    // `DiagnosticServerCancellationData`; `false` is the server saying not to
                    (it.responseError.data as? JsonObject)?.get("retriggerRequest")?.asBoolean != false
            }
    }
}
