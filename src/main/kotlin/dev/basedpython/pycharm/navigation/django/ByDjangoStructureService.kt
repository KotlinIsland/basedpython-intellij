package dev.basedpython.pycharm.navigation.django

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.util.PsiModificationTracker
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
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

    /** The last answer and the PSI modification count it was asked at, for [fresh]. */
    private class Stamped(val stamp: Long, val state: State)

    @Volatile
    private var stamped: Stamped? = null

    private val freshLock = Any()

    init {
        // a restarted `by` may be another build: what the last one said, even that it has no
        // `by/djangoStructure`, is not this one's answer
        project.messageBus.connect(scope).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    stamped = null
                    _state.compareAndSet(State.Unsupported, State.Idle)
                    // the gutter's django icons, asked of a `by` that was not up yet, are asked again
                    DaemonCodeAnalyzer.getInstance(project).restart(this@ByDjangoStructureService)
                }
            },
        )
        scope.launch {
            for (ask in asks) {
                delay(SETTLE_MS)
                val stamp = readAction { PsiModificationTracker.getInstance(project).modificationCount }
                val state = withContext(Dispatchers.IO) { ask() }
                stamped = Stamped(stamp, state)
                _state.value = state
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

    /** The last structure `by` answered with, however old; never asks. */
    fun current(): ByDjangoStructure? = (_state.value as? State.Ready)?.structure

    /** Ask `by` again, soon; asks that arrive before this one is sent are the same ask. */
    fun refresh() {
        asks.trySend(Unit)
    }

    /**
     * The structure as the project's code is now, asking and waiting when the last answer is from
     * before the latest change; for the gutter's slow pass, which must not draw an icon where an edit
     * has since moved its name. Every pass after one change shares one ask. Null off a django project,
     * from a `by` without the request, and on the EDT when there is no answer for now yet.
     */
    fun fresh(): ByDjangoStructure? {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        stamped?.takeIf { it.stamp == stamp }?.let { return (it.state as? State.Ready)?.structure }
        if (ApplicationManager.getApplication().isDispatchThread) return null
        // a `by` from before the request says so once per server, not once per pass
        if (_state.value == State.Unsupported) return null
        if (!ByDjangoStructureToolWindow.looksLikeDjango(project) && _state.value !is State.Ready) return null
        synchronized(freshLock) {
            stamped?.takeIf { it.stamp == stamp }?.let { return (it.state as? State.Ready)?.structure }
            val state = ask()
            // a failed ask is asked again by the next pass rather than remembered for this revision
            if (state is State.Failed) return null
            val first = state is State.Ready && _state.value !is State.Ready
            stamped = Stamped(stamp, state)
            _state.value = state
            // the passes of this revision that ran before `by` answered drew nothing; draw them again
            if (first) DaemonCodeAnalyzer.getInstance(project).restart(this)
            return (state as? State.Ready)?.structure
        }
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
