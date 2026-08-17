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

    private fun configure() {
        BasedPythonSettings.getInstance(fixture.project).debuggerDataFlow = true
        fixture.configureByText("bain.by", source)
        val file = fixture.file.virtualFile
        ByDataFlowSession.getInstance(fixture.project).publish(file, findings())
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
