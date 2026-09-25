package dev.basedpython.pycharm.lsp

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** What asking for the project's `by` came to. */
internal sealed interface ByServerStart {

    /** `by` is running and has answered `initialize`. */
    class Running(val client: LspClient) : ByServerStart

    /** `by` is not running and was not started; [why] says what stopped it, in a sentence for the user. */
    class Unavailable(val why: String) : ByServerStart
}

/**
 * The project's `by`, started first if it is not running, once it has finished initializing.
 *
 * For work that asks about the whole project rather than about a file somebody opened: an inspection
 * run, which in a batch has no editor at all, and a module renamed or moved from the Project view or
 * the Modules settings page.
 *
 * Suspends, cancellably, until `by` reports that it has initialized or stopped, or [timeoutMs] has
 * passed. Never blocks a thread while it waits, so a caller on a background thread wraps it in
 * `runBlockingCancellable` under its own progress.
 *
 * The platform's start is asynchronous and does nothing in three cases it does not report, each of
 * which is checked for here rather than waited out: the project is not trusted, `by` is switched off
 * or has no binary, and a `by` that stopped on its own is still held — the platform keeps a client
 * that crashed, and will not start another beside it until it is restarted.
 *
 * A start cannot finish while a modal dialog or a modal progress is showing: the platform adds the
 * client in a write action on the EDT that waits for them to close (measured: a start asked for under a
 * rename's progress came up the moment the conflicts dialog it caused was dismissed, and not before).
 * A caller in one passes [waitForNewStart] false: a `by` that is already initializing is still waited
 * for, since that needs nothing from the EDT, but a new start is only asked for — so it is running once
 * the dialog closes — and answered at once with why there is no server yet, rather than after
 * [timeoutMs] of waiting on something that cannot happen. [ByStartOnOpen] is what makes that case
 * rare.
 */
internal suspend fun awaitByServer(
    project: Project,
    timeoutMs: Long = BY_START_TIMEOUT_MS,
    waitForNewStart: Boolean = true,
): ByServerStart {
    val manager = LspClientManager.getInstance(project)
    fun clients() = manager.getClients(ByLspServerSupportProvider::class.java)
    fun running() = clients().firstOrNull { it.state == LspServerState.Running }
    running()?.let { return ByServerStart.Running(it) }

    val settled = CompletableDeferred<Unit>()
    val connection = project.messageBus.connect()
    try {
        connection.subscribe(ByLspLifecycleListener.TOPIC, object : ByLspLifecycleListener {
            override fun serverInitialized(serverName: String) {
                if (serverName == BY_SERVER_NAME) settled.complete(Unit)
            }

            override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                if (serverName == BY_SERVER_NAME) settled.complete(Unit)
            }
        })
        // subscribed before looking again, so a start that finishes in between is still heard
        running()?.let { return ByServerStart.Running(it) }
        if (clients().none { it.state == LspServerState.Initializing }) {
            if (clients().any { it.state == LspServerState.ShutdownUnexpectedly }) {
                return ByServerStart.Unavailable(BasedPythonBundle.message("by.start.crashed"))
            }
            if (!TrustedProjects.isProjectTrusted(project)) {
                return ByServerStart.Unavailable(BasedPythonBundle.message("by.start.untrusted"))
            }
            startByServer(project)?.let { return ByServerStart.Unavailable(it) }
            if (!waitForNewStart) return ByServerStart.Unavailable(BasedPythonBundle.message("by.start.pending"))
        }
        withTimeoutOrNull(timeoutMs) { settled.await() }
            ?: return ByServerStart.Unavailable(BasedPythonBundle.message("by.start.timeout", timeoutMs / 1000))
        return running()?.let { ByServerStart.Running(it) }
            ?: ByServerStart.Unavailable(BasedPythonBundle.message("by.start.stopped"))
    } finally {
        connection.disconnect()
    }
}

/**
 * How long [awaitByServer] waits for `by` to answer `initialize` by default.
 *
 * It bounds a server that hangs rather than one that is slow: what waits on it is a request the user
 * made, which has to end in something they can act on.
 */
internal const val BY_START_TIMEOUT_MS: Long = 60_000

/** The name `by`'s descriptor reports its lifecycle under; see [ByLspLifecycleListener.Broadcaster]. */
internal const val BY_SERVER_NAME = "by"
