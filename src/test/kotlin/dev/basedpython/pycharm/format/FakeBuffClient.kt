package dev.basedpython.pycharm.format

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jServer
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * A `buff` that answers `textDocument/codeAction` from [answer] and counts what it is asked.
 *
 * Versions are the document's own modification sequence — a number that, like the platform's own
 * per-file counter, moves on with every edit — so a test moves a document to a new version simply
 * by editing it. A blocking request fails the test: nothing in a cleanup pass has any business
 * waiting on a thread for `buff`.
 */
internal class FakeBuffClient(
  override val project: Project,
  private val answer: (CodeActionParams) -> List<Either<Command, CodeAction>>,
) : LspClient {

  val requests = AtomicInteger()

  override val providerClass: Class<out LspIntegrationProvider> get() = error("not used")
  override val descriptor: LspClientDescriptor get() = error("not used")
  override val state: LspServerState get() = LspServerState.Running
  override val initializeResult: InitializeResult? get() = null

  override fun sendNotification(lsp4jSender: (Lsp4jServer) -> Unit) = Unit

  override suspend fun <Lsp4jResponse> sendRequest(
    lsp4jSender: (Lsp4jServer) -> CompletableFuture<Lsp4jResponse>,
  ): Lsp4jResponse? = lsp4jSender(server).join()

  override fun <Lsp4jResponse> sendRequestSync(
    timeoutMs: Int,
    lsp4jSender: (Lsp4jServer) -> CompletableFuture<Lsp4jResponse>,
  ): Lsp4jResponse? = error("a cleanup pass must not block on a request")

  override fun getDocumentIdentifier(file: VirtualFile): TextDocumentIdentifier =
    TextDocumentIdentifier(file.url)

  override fun getDocumentVersion(document: Document): Int =
    (document as DocumentEx).modificationSequence

  override fun nextDocumentVersion(document: Document): Int =
    error("a cleanup pass sends no document changes of its own")

  private val documents = object : TextDocumentService {
    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
      requests.incrementAndGet()
      return CompletableFuture.completedFuture(answer(params))
    }

    override fun didOpen(params: DidOpenTextDocumentParams?) = Unit
    override fun didChange(params: DidChangeTextDocumentParams?) = Unit
    override fun didClose(params: DidCloseTextDocumentParams?) = Unit
    override fun didSave(params: DidSaveTextDocumentParams?) = Unit
  }

  private val server = object : Lsp4jServer {
    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> = error("not used")
    override fun shutdown(): CompletableFuture<Any> = error("not used")
    override fun exit() = Unit
    override fun getWorkspaceService(): WorkspaceService = error("not used")
    override fun getTextDocumentService(): TextDocumentService = documents
  }
}
