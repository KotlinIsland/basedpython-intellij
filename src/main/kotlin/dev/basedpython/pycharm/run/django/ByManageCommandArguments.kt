package dev.basedpython.pycharm.run.django

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.util.PsiModificationTracker
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommandArguments
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommandParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A management command's arguments, from `by/djangoCommand`: asked when the user picks the command,
 * since answering runs the project's django, and kept until the project's code changes.
 */
@Service(Service.Level.PROJECT)
internal class ByManageCommandArguments(private val project: Project, private val scope: CoroutineScope) {

    sealed interface State {
        data object NoServer : State

        /** The running `by` is from before the request. */
        data object Unsupported : State
        data object Failed : State

        /** There is no such command. */
        data object Unknown : State
        data class Ready(val arguments: ByDjangoCommandArguments) : State
    }

    private class Kept(val stamp: Long, val state: State)

    private val kept = ConcurrentHashMap<ByDjangoCommandParams, Kept>()

    @Volatile
    private var unsupported = false

    init {
        project.messageBus.connect(scope).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    unsupported = false
                    kept.clear()
                }
            },
        )
    }

    /**
     * Calls [onState] on a background thread with [command]'s arguments; cancelling the job leaves it
     * uncalled, and the request too when it has not been sent, since a name being typed passes through
     * others (`test` on the way to `testserver`).
     */
    fun ask(command: String, settings: String?, onState: (State) -> Unit): Job = scope.launch(Dispatchers.IO) {
        val params = ByDjangoCommandParams(command, settings)
        val stamp = stamp()
        val known = kept[params]?.takeIf { it.stamp == stamp }?.state
        val state = known ?: run {
            delay(SETTLE_MS)
            // what could not be answered is asked again the next time
            request(params).also { if (it is State.Ready && it.arguments.error == null || it == State.Unknown) kept[params] = Kept(stamp, it) }
        }
        ensureActive()
        onState(state)
    }

    /** Which state of the project's code an answer is of. */
    fun stamp(): Long = PsiModificationTracker.getInstance(project).modificationCount

    private suspend fun request(params: ByDjangoCommandParams): State {
        if (unsupported) return State.Unsupported
        val client = LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running } ?: return State.NoServer
        val notFound = AtomicBoolean()
        val answer = client.awaitBy(METHOD, TIMEOUT_MS) { server ->
            (server as ByServerExtensions).djangoCommand(params).whenComplete { _, error ->
                if (error != null && isMethodNotFound(error)) notFound.set(true)
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> State.Ready(answer.value)
            ByAnswer.None -> State.Unknown
            ByAnswer.Failed -> if (notFound.get()) State.Unsupported.also { unsupported = true } else State.Failed
        }
    }

    companion object {
        private const val METHOD = "by/djangoCommand"

        /** Django is set up and every command's parser read. */
        private const val TIMEOUT_MS = 30_000L

        private const val SETTLE_MS = 300L

        fun getInstance(project: Project): ByManageCommandArguments = project.service()
    }
}
