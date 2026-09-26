package dev.basedpython.pycharm.debug.dfa

import com.intellij.codeHighlighting.Pass
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.replaceService
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [ByDataFlowSession] takes from a stop's analysis, and what it leaves behind.
 *
 * An analysis waits on two other processes, so a program stepped while one is out is somewhere
 * else by the time it answers. Each test here holds an analysis back past the moment the program
 * moved — the next stop, a resume, the setting being turned off — and checks that its answer is
 * not what gets drawn.
 */
@TestFixtures
class ByDataFlowSessionTest {

    private val fixture by codeInsightFixture()

    private val service get() = ByDataFlowSession.getInstance(fixture.project)

    private val source = """
        def f(a=1):
            if a == 2:
                print("hi")
    """.trimIndent() + "\n"


    private fun configure() {
        BasedPythonSettings.getInstance(fixture.project).debuggerDataFlow = true
        fixture.configureByText("bain.by", source)
    }

    @AfterEach
    fun forget() = onEdt {
        service.clear()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        BasedPythonSettings.getInstance(fixture.project).loadState(BasedPythonSettings.State())
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("gave up waiting for $what")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /**
     * An analysis that answers only when [gate] opens, and does not stop waiting for it when
     * cancelled — so what is tested is that its answer is refused, not merely that it was cancelled
     * before it could give one. [started] completes once it is under way, so a test moves the
     * program on only after the analysis has begun rather than before it was scheduled.
     */
    private class HeldBack(private val label: String) {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val analysis: suspend () -> List<ByDataFlowFinding> = {
            started.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            listOf(finding(label))
        }
    }

    @Test
    fun `an answer that arrives after the next stop's is not drawn`() = onEdt {
        configure()
        val file = fixture.file.virtualFile
        val held = HeldBack("the first stop")

        val first = service.stopped(file, held.analysis)
        runBlocking { held.started.await() }
        runBlocking { service.stopped(file) { listOf(finding("the second stop")) }.join() }
        assertEquals(listOf("the second stop"), service.findingsFor(file).map { it.label })

        held.gate.complete(Unit)
        runBlocking { first.join() }
        assertEquals(listOf("the second stop"), service.findingsFor(file).map { it.label })
    }

    @Test
    fun `an answer that arrives after the program resumed is not drawn`() = onEdt {
        configure()
        val file = fixture.file.virtualFile
        val held = HeldBack("a stop that has gone")

        val stop = service.stopped(file, held.analysis)
        runBlocking { held.started.await() }
        service.clear()
        held.gate.complete(Unit)
        runBlocking { stop.join() }
        assertTrue(service.findingsFor(file).isEmpty(), service.findingsFor(file).toString())
    }

    /** The next stop cancels the one before it, so a slow debugger is not asked twice at once for nothing. */
    @Test
    fun `the next stop cancels the analysis still running for the last one`() = onEdt {
        configure()
        val file = fixture.file.virtualFile
        val never = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()

        service.stopped(file) {
            started.complete(Unit)
            try {
                never.await()
            } finally {
                cancelled.set(never.isActive)
            }
            emptyList()
        }
        waitUntil("the first analysis to start") { started.isCompleted }
        service.stopped(file) { emptyList() }
        waitUntil("the first analysis to be cancelled") { cancelled.get() }
    }

    /**
     * The setting turned off while findings are on screen. No pass runs once the factory declines,
     * so the pass that used to take them down is not coming.
     */
    @Test
    fun `the findings go when the setting is turned off while stopped`() = onEdt {
        configure()
        service.publish(fixture.file.virtualFile, listOf(finding("= true")))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        highlight()
        assertEquals(1, drawn().size)

        BasedPythonSettings.getInstance(fixture.project).debuggerDataFlow = false
        service.settingChanged()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
        assertTrue(service.isEmpty())
    }

    /**
     * What unloading the plugin does to the service: disposes it. Everything the pass drew carries
     * a renderer of ours, and one left in an editor that outlives the plugin pins its class loader.
     */
    @Test
    fun `disposing the service takes down everything the pass drew`() = onEdt {
        val parent = Disposer.newDisposable("a data flow service of this test's own")
        val scope = CoroutineScope(SupervisorJob())
        try {
            val own = ByDataFlowSession(fixture.project, scope)
            Disposer.register(parent, own)
            fixture.project.replaceService(ByDataFlowSession::class.java, own, parent)
            configure()
            own.publish(fixture.file.virtualFile, listOf(finding("= true")))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            highlight()
            assertTrue(drawn().any { it.customRenderer is ByDataFlowVerdictRenderer }, drawn().toString())
        } finally {
            Disposer.dispose(parent)
            scope.cancel()
        }
        assertEquals(
            emptyList<Any?>(),
            fixture.editor.markupModel.allHighlighters.mapNotNull { it.customRenderer as? ByDataFlowVerdictRenderer },
        )
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
    }

    private companion object {
        fun finding(label: String) = ByDataFlowFinding(
            range = Range(Position(1, 7), Position(1, 13)),
            kind = "condition",
            label = label,
        )
    }

    private fun drawn() = fixture.editor.markupModel.allHighlighters
        .filter { it.textAttributesKey?.externalName?.startsWith("BASEDPYTHON_DATA_FLOW") == true }

    /** One daemon run over the fixture's file; see `ByDataFlowPassTest.highlight` for why not `doHighlighting`. */
    private fun highlight() {
        CodeInsightTestFixtureImpl.instantiateAndRun(fixture.file, fixture.editor, intArrayOf(Pass.LINE_MARKERS), true)
    }
}
