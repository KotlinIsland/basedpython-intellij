package dev.basedpython.pycharm.debug.dfa

import com.intellij.codeHighlighting.Pass
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That a finding reaches the editor, and reaches it where it can be seen.
 *
 * Everything between [ByDataFlowSession] and a mark on screen is registration and reconciliation —
 * the `highlightingPassFactory` entry in plugin.xml, the language and settings checks, and the
 * redraw the session asks for — and none of it is visible to a unit test of the vocabulary. A
 * finding computed perfectly and never added to the markup model looks exactly like a feature that
 * was never written, which is what this one looked like.
 *
 * The source is the snippet from the report that started this, so the ranges below are the ones a
 * real `by` answered for a real stop, not shapes chosen here.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByDataFlowPassTest {

    private val fixture by codeInsightFixture()

    private val source = """
        def f(a=1):
            a += 1
            if a == 2:
                print("hi")
            else:
                print("bye")
    """.trimIndent() + "\n"

    /** `a == 2` and `print("bye")`, exactly as `by` reported them for this file. */
    private fun findings() = listOf(
        ByDataFlowFinding(
            range = Range(Position(2, 7), Position(2, 13)),
            kind = "condition",
            taken = true,
            label = "= true",
        ),
        ByDataFlowFinding(
            range = Range(Position(5, 8), Position(5, 20)),
            kind = "unreachable",
            label = "will not run",
        ),
    )

    private fun drawn() = fixture.editor.markupModel.allHighlighters
        .filter { it.textAttributesKey?.externalName?.startsWith("BASEDPYTHON_DATA_FLOW") == true }

    /**
     * One daemon run over the fixture's file.
     *
     * `fixture.doHighlighting()` would be the obvious call and it fails here for a reason that has
     * nothing to do with this feature: `ByTestNodeService` collects a project's tests on a
     * background thread and asks the daemon to restart when the answer arrives, and in a fixture
     * that lands inside this very run rather than seconds later as it does in a live IDE. The
     * daemon asserts on a restart during highlighting, so this is the escape hatch its own message
     * names.
     *
     * The line-markers pass is skipped for the other half of the same reason. Its contributors ask
     * `ByTestNodeService` to collect the project's tests, and the fixture's project is shared
     * across the whole suite — so running it here leaves that service collecting for whichever test
     * runs next. Nothing this test asserts comes from a gutter icon.
     */
    private fun highlight() {
        CodeInsightTestFixtureImpl.instantiateAndRun(
            fixture.file, fixture.editor, intArrayOf(Pass.LINE_MARKERS), true,
        )
    }

    private fun configure(text: String = source, found: List<ByDataFlowFinding> = findings()) {
        BasedPythonSettings.getInstance(fixture.project).debuggerDataFlow = true
        fixture.configureByText("bain.by", text)
        val file = fixture.file.virtualFile
        ByDataFlowSession.getInstance(fixture.project).publish(file, found)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        highlight()
    }

    @Test
    fun `a published finding is drawn over the source it is about`() {
        configure()
        val text = fixture.editor.document.immutableCharSequence
        assertEquals(
            mapOf(
                "BASEDPYTHON_DATA_FLOW_DECIDED" to "a == 2",
                "BASEDPYTHON_DATA_FLOW_WILL_NOT_RUN" to """print("bye")""",
            ),
            drawn().associate {
                it.textAttributesKey!!.externalName to text.subSequence(it.startOffset, it.endOffset).toString()
            },
        )
    }

    @Test
    fun `the fade is drawn above the colouring it has to override`() {
        // the defect this pins. `.by` files are coloured by `by`'s LSP semantic tokens, which the
        // daemon puts in the document markup at `WEAK_WARNING` — measured in a running IDE, where
        // `print` and `"bye"` carried `BASEDPYTHON_FUNCTION_DECLARATION` and `BASEDPYTHON_STRING`
        // at layer 3750. A higher layer's foreground is painted last, so at `ADDITIONAL_SYNTAX`
        // (3000) the grey was drawn and then completely overpainted: the markup model held the
        // finding and the dead branch looked no different from the live one
        configure()
        assertTrue(
            drawn().isNotEmpty() && drawn().all { it.layer > HighlighterLayer.WEAK_WARNING },
            "drawn under the semantic tokens, so nothing of it survives: " +
                drawn().map { it.textAttributesKey?.externalName to it.layer },
        )
        assertTrue(
            drawn().all { it.layer < HighlighterLayer.WARNING },
            "a warning or an error on a line that will not run is still the more urgent thing to " +
                "see, and this must not grey it out: " +
                drawn().map { it.textAttributesKey?.externalName to it.layer },
        )
    }

    /**
     * The function from the second report, and what a real stop on its first statement answered.
     *
     * The whole exchange was driven live — `by run` with `bpd` attached, breakpoint on line 2, the
     * names [ByDataFlowNames] would collect, `bpd/facts` for them, [ByDataFlowFacts] over the
     * reply, `by/dataFlowAt` with what came out — and this is the JSON that came back. Written out
     * rather than shortened, because a fixture trimmed to what this test reads is a fixture that
     * agrees with this test's own idea of the reply.
     *
     * Note what is *not* in it. `bpd` proved `discount` is a float `0.0` at the stop, and nothing
     * sent it: `by` has no float observation, so the plugin drops the fact. The `0.0` below comes
     * from the file's own `discount = 0.0` surviving two branches the seeds proved dead, which is
     * the point — the value is derived, not echoed.
     */
    private val priceSource = """
        def price(qty: int, member: bool):
            discount = 0.0
            if qty >= 10:
                discount = 0.1
            if member:
                discount += 0.05
            return discount
    """.trimIndent() + "\n"

    private fun priceFindings() = listOf(
        ByDataFlowFinding(
            range = Range(Position(2, 7), Position(2, 16)),
            kind = "condition",
            taken = false,
            label = "= false",
        ),
        ByDataFlowFinding(
            range = Range(Position(3, 8), Position(3, 22)),
            kind = "unreachable",
            label = "will not run",
        ),
        ByDataFlowFinding(
            range = Range(Position(4, 7), Position(4, 13)),
            kind = "condition",
            taken = false,
            label = "= false",
        ),
        ByDataFlowFinding(
            range = Range(Position(5, 8), Position(5, 24)),
            kind = "unreachable",
            label = "will not run",
        ),
        ByDataFlowFinding(
            range = Range(Position(6, 11), Position(6, 19)),
            kind = "value",
            value = "0.0",
            label = "discount = 0.0",
        ),
    )

    @Test
    fun `a settled value is drawn over the read it is about, under its own key`() {
        // the kind the pass grew for the second report. an unknown kind is dropped rather than
        // guessed at, so a `value` the pass did not learn about would be silently absent — which
        // looks exactly like `by` not having decided it
        configure(priceSource, priceFindings())
        val text = fixture.editor.document.immutableCharSequence
        assertEquals(
            listOf("BASEDPYTHON_DATA_FLOW_DECIDED_VALUE" to "discount"),
            drawn()
                .filter { it.textAttributesKey?.externalName == "BASEDPYTHON_DATA_FLOW_DECIDED_VALUE" }
                .map {
                    it.textAttributesKey!!.externalName to
                        text.subSequence(it.startOffset, it.endOffset).toString()
                },
        )
    }

    @Test
    fun `the value's label is the one drawn beside the line`() {
        // the label is what the reader actually sees, and it is the server's string rather than
        // anything assembled here — a plugin that rebuilt it would be a second place for the
        // spelling to drift
        configure(priceSource, priceFindings())
        val renderer = drawn()
            .single { it.textAttributesKey?.externalName == "BASEDPYTHON_DATA_FLOW_DECIDED_VALUE" }
            .customRenderer
        assertTrue(
            renderer is ByDataFlowVerdictRenderer,
            "a value with no renderer draws no label at all: $renderer",
        )
    }

    @Test
    fun `nothing is drawn when the feature is off`() {
        // the control, and the reason the setting is checked in the factory rather than only in
        // the listener: a session that was recorded while it was on must stop drawing when it is
        // turned off, not keep the last stop's marks on screen
        BasedPythonSettings.getInstance(fixture.project).debuggerDataFlow = false
        fixture.configureByText("bain.by", source)
        ByDataFlowSession.getInstance(fixture.project).publish(fixture.file.virtualFile, findings())
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        highlight()
        assertEquals(emptyList<String>(), drawn().map { it.textAttributesKey?.externalName })
    }
}
