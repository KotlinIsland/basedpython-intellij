package dev.basedpython.pycharm.lsp.diagnostics

import com.google.gson.JsonObject
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.Lsp4jServer
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.PlatformTestUtil
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import org.eclipse.lsp4j.WorkspaceDocumentDiagnosticReport
import org.eclipse.lsp4j.WorkspaceFullDocumentDiagnosticReport
import org.eclipse.lsp4j.WorkspaceUnchangedDocumentDiagnosticReport
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The long poll that keeps Problems | Project Errors current, against a `by` that answers from a
 * script: what each answer lists, what the next request carries, and that rows go when they should.
 */
@TestFixtures
class ByProjectDiagnosticsTest {

    private val fixture by codeInsightFixture()

    private val service get() = ByProjectDiagnostics.getInstance(fixture.project)

    @AfterEach
    fun stopFollowing() = onEdt {
        BasedPythonSettings.getInstance(fixture.project).byProjectDiagnostics = false
        service.settingChanged()
    }

    @Test
    fun `an answer lists each file's problems, and the next request carries their result ids`() = onEdt {
        val a = fixture.addFileToProject("a.by", "x: int = ''\n").virtualFile
        val b = fixture.addFileToProject("b.by", "y: str = 1\n").virtualFile
        val server = follow()

        val second = server.step(report(full(a, "ra", error("bad a"), hint("unused")), full(b, "rb", warning("bad b"))))

        assertEquals(listOf("bad a"), texts(a), "the hint is not a problem")
        assertEquals(listOf("bad b"), texts(b))
        assertEquals(HighlightSeverity.WARNING, service.shownFor(b).single().severity)
        assertEquals(mapOf(a.url to "ra", b.url to "rb"), second.previousResultIds.associate { it.uri to it.value })
        val collector = ProblemsCollector.getInstance(fixture.project)
        assertEquals(2, collector.getFileProblemCount(a) + collector.getFileProblemCount(b), "every row reached the tab")
    }

    @Test
    fun `an unchanged file keeps its rows, and a fixed one loses them and its result id`() = onEdt {
        val a = fixture.addFileToProject("a.by", "x: int = ''\n").virtualFile
        val b = fixture.addFileToProject("b.by", "y: str = 1\n").virtualFile
        val server = follow()
        server.step(report(full(a, "ra", error("bad a")), full(b, "rb", error("bad b"))))

        val third = server.step(report(unchanged(a, "ra"), full(b, null)))

        assertEquals(listOf("bad a"), texts(a))
        assertEquals(emptyList<String>(), texts(b))
        assertEquals(mapOf(a.url to "ra"), third.previousResultIds.associate { it.uri to it.value })
    }

    @Test
    fun `an edit that cancels a check is asked again, and anything else stops following`() = onEdt {
        val a = fixture.addFileToProject("a.by", "x: int = ''\n").virtualFile
        val server = follow()
        server.step(report(full(a, "ra", error("bad a"))))

        val again = server.stepFailing(serverCancelled(retrigger = true))
        assertEquals(mapOf(a.url to "ra"), again.previousResultIds.associate { it.uri to it.value })
        assertEquals(listOf("bad a"), texts(a), "asked again, not cleared")

        server.fail(ResponseErrorException(ResponseError(ResponseErrorCode.InternalError, "boom", null)))
        waitUntil { service.shownFor(a).isEmpty() }
        assertNull(server.requests.poll(200, TimeUnit.MILLISECONDS), "a failure is not asked again")
    }

    @Test
    fun `turning the setting off takes every row away`() = onEdt {
        val a = fixture.addFileToProject("a.by", "x: int = ''\n").virtualFile
        val server = follow()
        server.step(report(full(a, "ra", error("bad a"))))

        BasedPythonSettings.getInstance(fixture.project).byProjectDiagnostics = false
        service.settingChanged()

        assertTrue(texts(a).isEmpty())
        assertEquals(0, ProblemsCollector.getInstance(fixture.project).getFileProblemCount(a))
    }

    @Test
    fun `a server that cancelled a check and said not to ask again is not asked again`() = onEdt {
        assertTrue(ByProjectDiagnostics.isAskAgain(serverCancelled(retrigger = true)))
        assertFalse(ByProjectDiagnostics.isAskAgain(serverCancelled(retrigger = false)))
        assertTrue(
            ByProjectDiagnostics.isAskAgain(
                ResponseErrorException(ResponseError(ResponseErrorCode.ContentModified, "modified", null)),
            ),
        )
        assertFalse(
            ByProjectDiagnostics.isAskAgain(
                ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, "no", null)),
            ),
        )
    }

    // --- scaffolding ---

    /** Follows a scripted server, and waits for the first request, which carries no result ids. */
    private fun follow(): ScriptedBy {
        BasedPythonSettings.getInstance(fixture.project).byProjectDiagnostics = true
        val server = ScriptedBy(fixture.project)
        service.follow(server)
        assertTrue(server.nextRequest().previousResultIds.isEmpty())
        return server
    }

    /**
     * The rows listed for [file]. Read after the request that followed an answer, so the answer
     * has been applied: the next request is only sent once it has.
     */
    private fun texts(file: VirtualFile): List<String> = service.shownFor(file).map { it.text }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun serverCancelled(retrigger: Boolean) = ResponseErrorException(
        ResponseError(
            ResponseErrorCode.ServerCancelled,
            "server cancelled the request",
            JsonObject().apply { addProperty("retriggerRequest", retrigger) },
        ),
    )

    private fun report(vararg items: WorkspaceDocumentDiagnosticReport) = WorkspaceDiagnosticReport(items.toList())

    private fun full(file: VirtualFile, resultId: String?, vararg diagnostics: Diagnostic) =
        WorkspaceDocumentDiagnosticReport(
            WorkspaceFullDocumentDiagnosticReport(diagnostics.toList(), file.url, null).apply { this.resultId = resultId },
        )

    private fun unchanged(file: VirtualFile, resultId: String) =
        WorkspaceDocumentDiagnosticReport(WorkspaceUnchangedDocumentDiagnosticReport(resultId, file.url, null))

    private fun diagnostic(message: String, severity: DiagnosticSeverity) =
        Diagnostic(Range(Position(0, 0), Position(0, 1)), message, severity, "by")

    private fun error(message: String) = diagnostic(message, DiagnosticSeverity.Error)
    private fun warning(message: String) = diagnostic(message, DiagnosticSeverity.Warning)
    private fun hint(message: String) = diagnostic(message, DiagnosticSeverity.Hint)
}

/**
 * A `by` whose `workspace/diagnostic` answers are handed to it one at a time, and which holds a
 * request open — a long poll — until it has one.
 */
private class ScriptedBy(override val project: Project) : LspClient {

    /** Every request sent, in order. */
    val requests = LinkedBlockingQueue<WorkspaceDiagnosticParams>()
    private val pending = LinkedBlockingQueue<CompletableFuture<WorkspaceDiagnosticReport>>()

    fun answer(report: WorkspaceDiagnosticReport) = nextPending().complete(report)
    fun fail(error: Throwable) = nextPending().completeExceptionally(error)

    /** Answers the open request with [report], and returns the request sent after it was applied. */
    fun step(report: WorkspaceDiagnosticReport): WorkspaceDiagnosticParams {
        answer(report)
        return nextRequest()
    }

    /** Fails the open request with [error], and returns the request sent after it. */
    fun stepFailing(error: Throwable): WorkspaceDiagnosticParams {
        fail(error)
        return nextRequest()
    }

    /** Waits for the next request. */
    fun nextRequest(): WorkspaceDiagnosticParams =
        checkNotNull(requests.poll(5, TimeUnit.SECONDS)) { "no request was sent" }

    private fun nextPending() = checkNotNull(pending.poll(5, TimeUnit.SECONDS)) { "no request is open" }

    override val providerClass: Class<out LspIntegrationProvider> get() = error("not used")
    override val state: LspServerState get() = LspServerState.Running
    override val initializeResult: InitializeResult? get() = null

    override val descriptor: LspClientDescriptor = object : LspClientDescriptor(project, "by") {
        override fun isSupportedFile(file: VirtualFile) = true
        override fun findFileByUri(fileUri: String): VirtualFile? = VirtualFileManager.getInstance().findFileByUrl(fileUri)
    }

    override fun sendNotification(lsp4jSender: (Lsp4jServer) -> Unit) = Unit

    override suspend fun <Lsp4jResponse> sendRequest(
        lsp4jSender: (Lsp4jServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? = lsp4jSender(server).await()

    override fun <Lsp4jResponse> sendRequestSync(
        timeoutMs: Int,
        lsp4jSender: (Lsp4jServer) -> CompletableFuture<Lsp4jResponse>,
    ): Lsp4jResponse? = error("the long poll must not block a thread")

    override fun getDocumentIdentifier(file: VirtualFile) = TextDocumentIdentifier(file.url)
    override fun getDocumentVersion(document: Document): Int = error("not used")
    override fun nextDocumentVersion(document: Document): Int = error("not used")

    private val workspace = object : WorkspaceService {
        override fun diagnostic(params: WorkspaceDiagnosticParams): CompletableFuture<WorkspaceDiagnosticReport> {
            val future = CompletableFuture<WorkspaceDiagnosticReport>()
            pending.put(future)
            requests.put(params)
            return future
        }

        override fun didChangeConfiguration(params: org.eclipse.lsp4j.DidChangeConfigurationParams?) = Unit
        override fun didChangeWatchedFiles(params: org.eclipse.lsp4j.DidChangeWatchedFilesParams?) = Unit
    }

    private val server = object : Lsp4jServer {
        override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> = error("not used")
        override fun shutdown(): CompletableFuture<Any> = error("not used")
        override fun exit() = Unit
        override fun getWorkspaceService(): WorkspaceService = workspace
        override fun getTextDocumentService(): TextDocumentService = error("not used")
    }
}
