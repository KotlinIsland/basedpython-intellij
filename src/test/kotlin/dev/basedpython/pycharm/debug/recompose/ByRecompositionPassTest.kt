package dev.basedpython.pycharm.debug.recompose

import com.intellij.codeHighlighting.Pass
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.replaceService
import dev.basedpython.pycharm.debug.dfa.ByDataFlowVerdictRenderer
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That a run reaches the editor as a label on the composable's definition line, and leaves it
 * when the program resumes.
 *
 * Everything between [ByRecompositionSession] and a mark on screen is registration and
 * reconciliation — the `highlightingPassFactory` entry in plugin.xml, the language and settings
 * checks, the redraw the session asks for — and none of it is visible to a unit test of the
 * vocabulary. A label computed perfectly and never added to the markup model looks exactly like a
 * feature that was never written.
 *
 * Driven through the session's own entry points with a link that answers nothing, so the records
 * are published synchronously by the test rather than by a pooled thread racing it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByRecompositionPassTest {

    private val fixture by codeInsightFixture()

    private val source = """
        def Counter():
            count = state(0)
            Text(str(count.value))

        def Total():
            Text("total")
    """.trimIndent() + "\n"

    /** A link a test can hold without an adapter: every request is unavailable. */
    private object Silent : ByRecompositionLink {
        override fun pull(): ByRecompositionAnswer = ByRecompositionAnswer.Unavailable("no adapter in this test")
        override fun watch(on: Boolean): ByRecompositionAnswer = ByRecompositionAnswer.Unavailable("no adapter in this test")
    }

    private val service get() = ByRecompositionSession.getInstance(fixture.project)

    private val countWrite = ByCause.State(
        cell = 1, kind = "state", op = "set", at = null, old = "0", new = "2",
        declared = null, declaredName = "count",
        written = ByTraceLocation("/app/counter.by", 14, null, null), thread = 1, posted = false, readers = 1,
    )

    /** Two runs of `Counter` and one of `Total` in frame 3, defined in the fixture's own file. */
    private fun answer(file: String) = ByRecompositions.Reply.Read(
        ByRecompositions.Answer(
            runtimes = 1,
            tracing = true,
            kept = listOf(
                run(file, frame = 3, scope = 5, name = "Counter", line = 1, causes = listOf(countWrite)),
                run(file, frame = 3, scope = 6, name = "Counter", line = 1, causes = listOf(ByCause.Created)),
                run(file, frame = 3, scope = 7, name = "Total", line = 5, causes = listOf(ByCause.Inline)),
                run(file, frame = 2, scope = 8, name = "Stale", line = 6, causes = listOf(ByCause.Created)),
            ),
            dropped = 0,
            unreadable = 0,
        ),
    )

    private fun run(file: String, frame: Long, scope: Long, name: String, line: Int, causes: List<ByCause>) =
        ByRecord.Run(
            runtime = 0, frame = frame, scope = scope, parent = 0, name = name,
            defined = ByTraceLocation(file, line, null, null), called = null, key = null, origin = "self",
            causes = causes, skipped = emptyList(), disposed = emptyList(), elapsedNs = 100,
        )

    private fun drawn() = fixture.editor.markupModel.allHighlighters
        .filter { it.textAttributesKey?.externalName?.startsWith("BASEDPYTHON_RECOMPOSITION") == true }

    /** One daemon run over the fixture's file; see `ByDataFlowPassTest.highlight` for why not `doHighlighting`. */
    private fun highlight() {
        CodeInsightTestFixtureImpl.instantiateAndRun(fixture.file, fixture.editor, intArrayOf(Pass.LINE_MARKERS), true)
    }

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        highlight()
    }

    /** A session that has stopped, with the answer published as a pull would publish it. */
    private fun stopWithRecords(enabled: Boolean = true) {
        BasedPythonSettings.getInstance(fixture.project).debuggerRecompositions = enabled
        fixture.configureByText("counter.by", source)
        service.sessionStarted(Silent)
        service.paused(Silent)
        service.publish(Silent, answer(fixture.file.virtualFile.path))
        settle()
    }

    @AfterEach
    fun forget() {
        service.sessionEnded(Silent)
        BasedPythonSettings.getInstance(fixture.project).loadState(BasedPythonSettings.State())
    }

    @Test
    fun `every composable that ran in the latest frame is labelled on its definition line`() {
        stopWithRecords()
        val text = fixture.editor.document.immutableCharSequence
        assertEquals(
            listOf(
                "def Counter():" to "ran ×2 · count 0 → 2, set at counter.by:14",
                "def Total():" to "ran ×1 · takes a content block, so it runs whenever its parent runs",
            ),
            drawn().map {
                text.subSequence(it.startOffset, it.endOffset).toString() to (it.customRenderer as ByDataFlowVerdictRenderer).label
            },
            "`Stale` ran in an earlier frame and gets nothing",
        )
    }

    @Test
    fun `the label is drawn above the colouring and below a warning`() {
        stopWithRecords()
        assertTrue(drawn().isNotEmpty() && drawn().all { it.layer > HighlighterLayer.WEAK_WARNING }, drawn().map { it.layer }.toString())
        assertTrue(drawn().all { it.layer < HighlighterLayer.WARNING }, drawn().map { it.layer }.toString())
    }

    @Test
    fun `the labels go when the program resumes, and come back at the next stop`() {
        stopWithRecords()
        service.resumed(Silent)
        settle()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })

        // The records are kept across the resume; the next stop labels them again without a pull
        service.paused(Silent)
        settle()
        assertEquals(2, drawn().size)
    }

    @Test
    fun `the labels go when the session ends`() {
        stopWithRecords()
        service.sessionEnded(Silent)
        settle()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
        assertTrue(service.records.isEmpty())
    }

    @Test
    fun `nothing is drawn when the feature is off`() {
        stopWithRecords(enabled = false)
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
    }

    /**
     * Off while labels are on screen. No pass runs once the factory declines, so the pass that
     * used to take the labels down is not coming: the session removes them itself when told the
     * setting changed, and they must not stay until the editor closes.
     */
    @Test
    fun `the labels go when the setting is turned off while stopped`() {
        stopWithRecords()
        assertEquals(2, drawn().size)
        BasedPythonSettings.getInstance(fixture.project).debuggerRecompositions = false
        service.settingChanged()
        settle()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
    }

    /** The session ending while the setting is off: still no pass, still removed. */
    @Test
    fun `the labels go when the session ends with the setting off`() {
        stopWithRecords()
        BasedPythonSettings.getInstance(fixture.project).debuggerRecompositions = false
        service.sessionEnded(Silent)
        settle()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
    }

    @Test
    fun `a session ending after another began leaves the newer one alone`() {
        stopWithRecords()
        val older = object : ByRecompositionLink {
            override fun pull() = ByRecompositionAnswer.Unavailable("no adapter in this test")
            override fun watch(on: Boolean) = ByRecompositionAnswer.Unavailable("no adapter in this test")
        }
        service.sessionEnded(older)
        settle()
        assertEquals(2, drawn().size, "the older session's end must not clear the current one")
    }

    /**
     * What unloading the plugin does to the session: disposes it. Every label carries a renderer of
     * ours, and one left in an editor that outlives the plugin pins its class loader.
     */
    @Test
    fun `disposing the session takes down every label the pass drew`() {
        val parent = Disposer.newDisposable("a recompositions session of this test's own")
        val scope = CoroutineScope(SupervisorJob())
        try {
            val own = ByRecompositionSession(fixture.project, scope)
            Disposer.register(parent, own)
            fixture.project.replaceService(ByRecompositionSession::class.java, own, parent)
            BasedPythonSettings.getInstance(fixture.project).debuggerRecompositions = true
            fixture.configureByText("counter.by", source)
            own.sessionStarted(Silent)
            own.paused(Silent)
            own.publish(Silent, answer(fixture.file.virtualFile.path))
            settle()
            assertEquals(2, drawn().size)
        } finally {
            Disposer.dispose(parent)
            scope.cancel()
        }
        assertEquals(
            emptyList<Any?>(),
            fixture.editor.markupModel.allHighlighters.mapNotNull { it.customRenderer as? ByDataFlowVerdictRenderer },
        )
    }
}
