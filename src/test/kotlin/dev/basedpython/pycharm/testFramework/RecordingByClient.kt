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
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture

/**
 * A `by` client that records what a server would have been sent, one line per message, in order.
 *
 * Document notifications read `open <name> v<version> <text>`, `change <name> v<version> <text>` and
 * `close <name>`; a watched file reads `watched <name> <kind>`. A `by/` request reads
 * `request <method>` and is answered with nothing, which every caller treats as no answer. Anything
 * else a test did not expect fails the test.
 */
class RecordingByClient(project: Project) : LspClient {
    val sent: MutableList<String> = mutableListOf()

    private val documents = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TextDocumentService::class.java)) { _, method, args ->
        when (val params = args?.firstOrNull()) {
            is DidOpenTextDocumentParams ->
                sent += "open ${name(params.textDocument.uri)} v${params.textDocument.version} ${params.textDocument.text.trim()}"
            is DidChangeTextDocumentParams ->
                sent += "change ${name(params.textDocument.uri)} v${params.textDocument.version} ${params.contentChanges.single().text.trim()}"
            is DidCloseTextDocumentParams ->
                sent += "close ${name(params.textDocument.uri)}"
            else -> error("unexpected ${method.name}")
        }
        null
    } as TextDocumentService

    private val workspace = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WorkspaceService::class.java)) { _, method, args ->
        when (val params = args?.firstOrNull()) {
            is DidChangeWatchedFilesParams ->
                params.changes.forEach { sent += "watched ${name(it.uri)} ${it.type.name.lowercase()}" }
            else -> error("unexpected ${method.name}")
        }
        null
    } as WorkspaceService

    private val server = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ByDataFlowServer::class.java)) { _, method, _ ->
        val request = method.getAnnotation(JsonRequest::class.java)
        when {
            method.name == "getTextDocumentService" -> documents
            method.name == "getWorkspaceService" -> workspace
            request != null && request.value.startsWith("by/") -> {
                sent += "request ${request.value}"
                CompletableFuture.completedFuture(null)
            }
            else -> error("unexpected ${method.name}")
        }
    } as LanguageServer

    override val providerClass: Class<out LspIntegrationProvider> = ByLspServerSupportProvider::class.java
    override val project: Project = project
    override val descriptor: LspClientDescriptor = object : ProjectWideLspClientDescriptor(project, "recorder") {
        override fun isSupportedFile(file: VirtualFile): Boolean = true
    }
    override val state: LspServerState = LspServerState.Running
    override val initializeResult: InitializeResult? = null

    override fun sendNotification(lsp4jSender: (LanguageServer) -> Unit) = lsp4jSender(server)

    override suspend fun <Lsp4jResponse> sendRequest(
        lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? = lsp4jSender(server).getNow(null)

    override fun <Lsp4jResponse> sendRequestSync(
        timeoutMs: Int,
        lsp4jSender: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? = lsp4jSender(server).getNow(null)

    override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(descriptor.getFileUri(file))

    override fun getDocumentVersion(document: Document): Int = -1

    override fun nextDocumentVersion(document: Document): Int = -1

    private fun name(uri: String) = uri.substringAfterLast('/')
}
