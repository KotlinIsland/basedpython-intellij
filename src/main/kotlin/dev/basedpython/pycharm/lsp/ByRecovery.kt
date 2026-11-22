package dev.basedpython.pycharm.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws the project's open files again when a server that had let a request time out answers again.
 *
 * What the editor shows from `by` is asked for by a highlighting pass, which draws nothing for a
 * request that times out and is not run again until something changes. So a `by` that answered
 * nothing for a few seconds — as it starts, above all — left the editor without what was asked of it
 * in that time until the next edit. (Measured with a `by` whose replies were held for three seconds
 * as it started: no inlay hints for as long as the file was left alone.)
 *
 * A request that draws says so where it is made, with `drawn` of [askBy] and [awaitBy], which bring it
 * here when it times out. Only such a request: it is sent again to see whether the server answers, and
 * a request that does something, a command or a transpile, must not be.
 */
@Service(Service.Level.PROJECT)
internal class ByRecovery(private val project: Project, scope: CoroutineScope) {

    private val watch = RecoveryWatch<LspClient>(scope) {
        if (!project.isDisposed) ByLspLifecycle.getInstance(project).requestRedraw()
    }

    /** [client] let a request time out after [timeoutMs], which [request] sends again. Any thread. */
    fun timedOut(client: LspClient, timeoutMs: Long, request: (LanguageServer) -> CompletableFuture<*>) =
        watch.timedOut(client, timeoutMs) { answered(client, request) }

    /** Whether [client] answered [request] at all — an error is an answer. Waits as long as it takes. */
    private suspend fun answered(client: LspClient, request: (LanguageServer) -> CompletableFuture<*>): Boolean {
        var sent = false
        try {
            client.sendRequest<Any?> { server ->
                sent = true
                @Suppress("UNCHECKED_CAST") // what it answers is not looked at
                request(server) as CompletableFuture<Any?>
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ResponseErrorException) {
            return true
        } catch (_: Exception) {
            return false
        }
        return sent
    }

    companion object {
        fun getInstance(project: Project): ByRecovery = project.service()
    }
}

/**
 * [ByRecovery] without the server: calls [recovered] when a [K] that let a request time out would
 * answer it in time again.
 *
 * The request is sent again with no limit, and once it has been answered, once more under the limit
 * it had. The second is what tells a server that has recovered from a request that is always slower
 * than its limit, which would only time out again when drawn: [recovered] is called for the one and
 * not for the other, so a slow request is not drawn, timed out and drawn again for ever.
 *
 * One at a time per [K]: while one request is being waited on, others that time out say nothing new.
 */
internal class RecoveryWatch<K : Any>(private val scope: CoroutineScope, private val recovered: () -> Unit) {

    private val watching: MutableSet<K> = ConcurrentHashMap.newKeySet()

    /** [send] sends the request again and returns whether it was answered. */
    fun timedOut(key: K, timeoutMs: Long, send: suspend () -> Boolean) {
        if (!watching.add(key)) return
        scope.launch {
            val again = try {
                send() && withTimeoutOrNull(timeoutMs) { send() } == true
            } finally {
                watching.remove(key)
            }
            if (again) recovered()
        }
    }
}
