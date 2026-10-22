package dev.basedpython.pycharm.debug

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.xdebugger.DefaultDapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.XDebuggerUtil
import com.jetbrains.dap.protocol.Capabilities
import com.jetbrains.dap.protocol.CompletionItem
import com.jetbrains.dap.protocol.CompletionItemType
import com.jetbrains.dap.protocol.CompletionsArguments
import com.jetbrains.dap.protocol.CompletionsResponse
import com.jetbrains.dap.protocol.DapRemoteEndpoint
import com.jetbrains.dap.protocol.DapServer
import com.jetbrains.dap.protocol.FrameId
import com.jetbrains.dap.protocol.RequestType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * A `.by` session's expression fields are basedpython, and offer what the adapter's `completions`
 * answers in the frame on screen — through the plugin's registered contributor, against an adapter
 * that answers as debugpy does.
 */
@TestFixtures
class ByDapCompletionTest {

    private val fixture by codeInsightFixture()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    private var fields = 0

    /** What the fake adapter was asked, in order. */
    private val asked = mutableListOf<CompletionsArguments>()

    /** debugpy's answer for `sys.vers`: `start` counts from 0 and covers `vers`. */
    private val debugpyVers = listOf(
        CompletionItem(label = "version_info", type = CompletionItemType.Field, start = 4, length = 4),
        CompletionItem(label = "version", type = CompletionItemType.Field, start = 4, length = 4),
    )

    private fun server(answer: List<CompletionItem>): DapServer = Proxy.newProxyInstance(
        DapServer::class.java.classLoader,
        arrayOf(DapServer::class.java),
    ) { self, method, args ->
        when (method.name) {
            "completions" -> CompletionsResponse(answer).also { asked += args[0] as CompletionsArguments }
            "equals" -> self === args?.get(0)
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "a fake adapter"
            else -> error("unexpected request: ${method.name}")
        }
    } as DapServer

    private fun executor(server: DapServer, supportsCompletions: Boolean?): DapSessionExecutor {
        val context = DapSessionContext::class.java
            .getConstructor(CoroutineScope::class.java, DapServer::class.java, DapRemoteEndpoint::class.java, StateFlow::class.java)
            .newInstance(scope, server, unusedEndpoint(), MutableStateFlow(Capabilities(supportsCompletionsRequest = supportsCompletions)))
        return object : DapSessionExecutor {
            override fun post(command: suspend DapSessionContext.() -> Unit) {
                scope.launch { context.command() }
            }

            override fun <T> postAsync(command: suspend DapSessionContext.() -> T): Deferred<T> =
                scope.async { context.command() }
        }
    }

    private fun unusedEndpoint(): DapRemoteEndpoint = object : DapRemoteEndpoint {
        override suspend fun <T, A> request(requestType: RequestType<A, T>, params: A): Result<T> =
            error("unexpected request: ${requestType.command}")
    }

    /** A DAP frame with id [FRAME], as the platform's frame factory makes for the frame on screen. */
    private fun frame(executor: DapSessionExecutor): DefaultDapXStackFrame {
        val thread = stub<DapThread>()
        val frame = Proxy.newProxyInstance(DapStackFrame::class.java.classLoader, arrayOf(DapStackFrame::class.java)) { self, method, args ->
            when {
                method.name.startsWith("getId") -> FRAME
                method.name == "getThread" -> thread
                method.name == "getName" -> "main"
                else -> objectMethod(self, method.name, args)
            }
        } as DapStackFrame
        return DefaultDapXStackFrame(DefaultDapXDebuggerPresentationFactory(null), executor, thread, frame)
    }

    private fun session(frame: DefaultDapXStackFrame?, suspended: Boolean = true): XDebugSession =
        Proxy.newProxyInstance(XDebugSession::class.java.classLoader, arrayOf(XDebugSession::class.java)) { self, method, args ->
            when (method.name) {
                "isSuspended" -> suspended
                "isStopped" -> false
                "getCurrentStackFrame" -> frame
                else -> objectMethod(self, method.name, args)
            }
        } as XDebugSession

    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        objectMethod(self, method.name, args)
    } as T

    private fun objectMethod(self: Any, name: String, args: Array<out Any?>?): Any? = when (name) {
        "equals" -> self === args?.get(0)
        "hashCode" -> System.identityHashCode(self)
        "toString" -> "a stand-in"
        else -> null
    }

    /** Opens a session field holding [text] (`<caret>` marks the caret) for [session]. */
    private fun field(text: String, session: XDebugSession?) {
        // a name of its own each time, so no field reuses another's document and the mark on it
        fixture.configureByText("expression${++fields}.by", text)
        session?.let { fixture.editor.document.putUserData(FIELD_SESSION, it) }
    }

    @Test
    fun `a session's field offers what the adapter answers, asked at the caret in the frame on screen`() = onEdt {
        field("sys.vers<caret>", session(frame(executor(server(debugpyVers), supportsCompletions = true))))
        val offered = fixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue(offered.containsAll(listOf("version", "version_info")), "offered $offered")
        val request = asked.single()
        assertEquals(FrameId(FRAME), request.frameId)
        assertEquals("sys.vers", request.text)
        assertEquals(9, request.column.value)
        assertEquals(1, request.line?.value)
    }

    @Test
    fun `picking an item replaces what the adapter said it covers, and keeps the dot`() = onEdt {
        field("sys.vers<caret>", session(frame(executor(server(debugpyVers), supportsCompletions = true))))
        val items = fixture.completeBasic()!!
        (fixture.lookup as LookupImpl).currentItem = items.first { it.lookupString == "version_info" }
        fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        fixture.checkResult("sys.version_info<caret>")
    }

    @Test
    fun `an adapter that does not advertise completions is not asked`() = onEdt {
        field("sys.vers<caret>", session(frame(executor(server(debugpyVers), supportsCompletions = null))))
        val offered = fixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertTrue("version_info" !in offered, "offered $offered")
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a field of no session, or of a running one, asks nothing`() = onEdt {
        val frame = frame(executor(server(debugpyVers), supportsCompletions = true))
        field("sys.vers<caret>", null)
        fixture.completeBasic()
        field("sys.vers<caret>", session(frame, suspended = false))
        fixture.completeBasic()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the session's editors make basedpython fields marked with the session, and the breakpoints' are unmarked`() = onEdt {
        val session = session(null)
        val provider = ByExpressionSupport.createEditorsProvider(session)
        val document = provider.createDocument(fixture.project, expression("a.b"), null as XSourcePosition?, EvaluationMode.EXPRESSION)
        assertEquals(BasedPythonLanguage, PsiDocumentManager.getInstance(fixture.project).getPsiFile(document)?.language)
        assertSame(session, document.getUserData(FIELD_SESSION))
        val breakpoints = ByDebuggerEditorsProvider.createDocument(fixture.project, expression("a.b"), null as XSourcePosition?, EvaluationMode.EXPRESSION)
        assertNull(breakpoints.getUserData(FIELD_SESSION))
    }

    private fun expression(text: String) =
        XDebuggerUtil.getInstance().createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)

    private companion object {
        const val FRAME = 7
    }
}
