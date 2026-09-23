package dev.basedpython.pycharm.testFramework

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import dev.basedpython.pycharm.debug.dfa.ByDataFlowServer
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ext.BuffServerExtensions
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A `by` or `buff` that answers each custom request (`by/…`, `buff/…`) with what [answer] returns for
 * its method name, and waits for it the way the platform's own client does.
 *
 * That is the point of it. `LspRequestExecutorBase.sendRequestSync` returns `null` for an error
 * answer (after logging it), for a timeout, and for a request it never sent because the server is
 * not running — so does this, and a test of a caller shows whether the caller tells those apart
 * from an empty answer. The suspending `sendRequest` throws an error answer and returns `null` for
 * a request never sent; so does this. [running] false is a server that is not running: nothing is
 * sent. [descriptor] stands in for the descriptor the server was started from, when a test needs a
 * real one.
 */
class AnsweringClient(
    override val project: Project,
    private val running: Boolean = true,
    descriptor: LspClientDescriptor? = null,
    private val answer: (method: String) -> CompletableFuture<*>,
) : LspClient {

    /** How many requests were sent, of any method. */
    val requests = AtomicInteger()

    private val server = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(ByDataFlowServer::class.java, BuffServerExtensions::class.java),
    ) { _, method, _ ->
        val request = method.getAnnotation(JsonRequest::class.java) ?: error("unexpected ${method.name}")
        requests.incrementAndGet()
        answer(request.value)
    } as LanguageServer

    override val providerClass: Class<out LspIntegrationProvider> = ByLspServerSupportProvider::class.java
    override val descriptor: LspClientDescriptor = descriptor ?: object : ProjectWideLspClientDescriptor(project, "answering") {
        override fun isSupportedFile(file: VirtualFile): Boolean = true
    }
    override val state: LspServerState = if (running) LspServerState.Running else LspServerState.ShutdownNormally
    override val initializeResult: InitializeResult? = null

    override fun sendNotification(lsp4jSender: (LanguageServer) -> Unit) {
        if (running) lsp4jSender(server)
    }

    override suspend fun <Lsp4jResponse> sendRequest(
        lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? = if (running) lsp4jSender(server).await() else null

    override fun <Lsp4jResponse> sendRequestSync(
        timeoutMs: Int,
        lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? {
        if (!running) return null
        val future = lsp4jSender(server)
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: ExecutionException) {
            null
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        }
    }

    override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(descriptor.getFileUri(file))

    override fun getDocumentVersion(document: Document): Int = -1

    override fun nextDocumentVersion(document: Document): Int = -1

    companion object {
        /** A server's answer of [value]. */
        fun <T> answered(value: T?): CompletableFuture<*> = CompletableFuture.completedFuture(value)

        /** A server's error answer, as lsp4j completes the request's future with it. */
        fun failed(code: ResponseErrorCode, message: String = code.name): CompletableFuture<*> =
            CompletableFuture<Any?>().apply { completeExceptionally(ResponseErrorException(ResponseError(code, message, null))) }
    }
}
