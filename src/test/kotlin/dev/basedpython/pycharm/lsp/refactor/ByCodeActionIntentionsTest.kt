package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionDisabled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** `by`'s code actions as intentions named for what they do, one for each kind `by` offers them under. */
@TestFixtures
class ByCodeActionIntentionsTest {

    private val fixture by codeInsightFixture()

    private fun action(title: String, kind: String?, disabled: Boolean = false) = CodeAction(title).apply {
        this.kind = kind
        if (disabled) this.disabled = CodeActionDisabled("no")
    }

    @Test
    fun `each action goes to the intention for its kind, and the rest to the others in by's order, quick fixes and disabled ones to none`() = onEdt {
        val client = AnsweringClient(fixture.project) { error("nothing is asked") }
        val offered = ByOfferedActions.of(
            listOf(
                action("Fix it", "quickfix"),
                action("Invert `if` condition", "refactor.rewrite.invertIf"),
                action("Convert to a single-quoted string", "refactor.rewrite.flipQuotes"),
                action("Convert the other string", "refactor.rewrite.flipQuotes"),
                action("Something new", "refactor.rewrite.somethingNew"),
                action("Invert `ready`", "refactor.rewrite.invertBoolean"),
                action("Invert `done`", "refactor.rewrite.invertBoolean"),
                action("Extract variable", "refactor.extract.variable", disabled = true),
            ),
        ) { LspIntentionAction(client, it) }

        assertEquals("Invert `if` condition", ByInvertIfIntention().pick(offered)?.text)
        assertEquals("Convert to a single-quoted string", ByFlipQuotesIntention().pick(offered)?.text)
        // Refactor | Invert Boolean's, never an intention's, nor among the others
        assertNull(offered.named[ByCodeActionKind.InvertBoolean])
        assertNull(ByExtractVariableIntention().pick(offered))
        assertEquals("Convert the other string", ByOtherCodeAction0Intention().pick(offered)?.text)
        assertEquals("Something new", ByOtherCodeAction1Intention().pick(offered)?.text)
        assertNull(ByOtherCodeAction2Intention().pick(offered))
    }

    @Test
    fun `every kind of by's code actions but a refactoring of the menu has an intention of its own, registered for basedpython`() = onEdt {
        val registered = IntentionManager.getInstance().intentionActions
            .map { IntentionActionDelegate.unwrap(it) }
            .filterIsInstance<ByNamedCodeActionIntention>()
            .map { it.familyName }
            .toSet()
        assertEquals(ByCodeActionKind.entries.filter { it.asIntention }.map { it.familyName }.toSet(), registered)
        assertEquals(listOf(ByCodeActionKind.InvertBoolean), ByCodeActionKind.entries.filterNot { it.asIntention })
        assertTrue(IntentionManager.getInstance().intentionActions.map { IntentionActionDelegate.unwrap(it) }.any { it is ByOtherCodeAction2Intention })
    }

    /**
     * The intentions ask `by` as the light bulb does, `Automatic`, whatever asked them — Alt+Enter
     * included — and `by` answers a refactoring that reads the whole project only when `Invoked`.
     * A kind `by` answers only then can never show in Alt+Enter, so it is not one of the intentions:
     * invert boolean, an intention since 0084f35, never showed there.
     */
    @Test
    fun `the intentions ask by as the light bulb does, which by answers invert boolean to only when invoked`() = onEdt {
        fixture.configureByText("flags.by", "ready = True\nprint(ready)\n")
        val client = AnsweringClient(fixture.project) { method ->
            assertEquals("textDocument/codeAction", method)
            AnsweringClient.answered(emptyList<Any>())
        }
        val params = withContext(Dispatchers.Default) {
            readAction { ByCodeActionIntentions(fixture.project).at(fixture.editor, client) }
            client.sentParams.single() as org.eclipse.lsp4j.CodeActionParams
        }
        assertEquals(org.eclipse.lsp4j.CodeActionTriggerKind.Automatic, params.context.triggerKind)
    }
}
