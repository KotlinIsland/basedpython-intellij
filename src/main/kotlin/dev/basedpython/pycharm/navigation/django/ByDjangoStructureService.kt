package dev.basedpython.pycharm.navigation.django

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What `by/djangoStructure` last said for the project, for the *Django* tool window.
 *
 * Asked again on [refresh], which the window calls when it opens, when `by` starts, and when the
 * project's files change while it is showing. Requests arriving together are one ask: they are
 * collected for [SETTLE_MS] first, so a burst of edits is one request rather than one each.
 */
@Service(Service.Level.PROJECT)
internal class ByDjangoStructureService(private val project: Project, private val scope: CoroutineScope) {

    /** What the window has to show. */
    sealed interface State {
        /** Not asked yet. */
        data object Idle : State

        /** No `by` is running for the project, so there is nobody to ask. */
        data object NoServer : State

        /** The running `by` has no `by/djangoStructure`: one from before it. */
        data object Unsupported : State

        /** `by` was asked and could not answer — a timeout, or an error it said in its log. */
        data object Failed : State

        /** `by` answered that no project of the session is a django one. */
        data object NotDjango : State

        data class Ready(val structure: ByDjangoStructure) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val asks = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ask in asks) {
                delay(SETTLE_MS)
                _state.value = withContext(Dispatchers.IO) { ask() }
            }
        }
    }

    /**
     * Calls [onState] with the state now and every one after, until the returned disposable is
     * disposed — tied by the caller to the window that shows it. Called on a background thread.
     */
    fun watch(parent: Disposable, onState: (State) -> Unit): Disposable {
        val job = scope.launch { state.collect(onState) }
        val watching = Disposable { job.cancel() }
        Disposer.register(parent, watching)
        return watching
    }

    /** Ask `by` again, soon; asks that arrive before this one is sent are the same ask. */
    fun refresh() {
        asks.trySend(Unit)
    }

    private fun ask(): State {
        val client = LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running } ?: return State.NoServer
        val unsupported = AtomicBoolean()
        val answer = client.askBy(METHOD, TIMEOUT_MS) { server ->
            (server as ByServerExtensions).djangoStructure(emptyMap()).whenComplete { _, error ->
                if (error != null && isMethodNotFound(error)) unsupported.set(true)
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> if (answer.value.projects.isEmpty()) State.NotDjango else State.Ready(answer.value)
            ByAnswer.None -> State.NotDjango
            ByAnswer.Failed -> if (unsupported.get()) State.Unsupported else State.Failed
        }
    }

    companion object {
        const val METHOD = "by/djangoStructure"

        /** Every app of the session's projects is read. */
        private const val TIMEOUT_MS = 30_000

        private const val SETTLE_MS = 400L

        fun getInstance(project: Project): ByDjangoStructureService = project.service()
    }
}
