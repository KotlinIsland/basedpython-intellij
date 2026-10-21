package dev.basedpython.pycharm.editor.mover

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/**
 * Move Element Left/Right through the real actions, with the lists of `by`'s outline put in by hand.
 */
@TestFixtures
class ByMoveElementLeftRightHandlerTest {

    private val fixture by codeInsightFixture()

    private fun move(
        right: Boolean,
        before: String,
        after: String,
        outline: (OutlineSpec.Suite.() -> Unit)?,
    ) = onEdt {
        fixture.configureByText("a.by", before)
        if (outline != null) OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        fixture.performEditorAction(
            if (right) IdeActions.MOVE_ELEMENT_RIGHT else IdeActions.MOVE_ELEMENT_LEFT,
        )
        fixture.checkResult(after)
    }

    @Test
    fun `an argument swaps with the one after it`() = move(
        right = true,
        before = "f(al<caret>pha, beta, gamma)\n",
        after = "f(beta, al<caret>pha, gamma)\n",
    ) { list("alpha", "beta", "gamma") }

    @Test
    fun `an argument swaps with the one before it`() = move(
        right = false,
        before = "f(alpha, beta, gam<caret>ma)\n",
        after = "f(alpha, gam<caret>ma, beta)\n",
    ) { list("alpha", "beta", "gamma") }

    /**
     * An element is a node of `by`'s parse, so a lambda moves whole: its commas are its own, which
     * a list read off the text between commas would split at.
     */
    @Test
    fun `an element with commas of its own moves whole`() = move(
        right = true,
        before = "f(lambda a, b: <caret>a, c)\n",
        after = "f(c, lambda a, b: <caret>a)\n",
    ) {
        list("lambda a, b: a", "c")
        list("a", "b")
    }

    /** The innermost list around the caret is the one moved in, not the call it is an argument of. */
    @Test
    fun `the innermost list around the caret is the one moved in`() = move(
        right = true,
        before = "f(g(x<caret>1, x2), y)\n",
        after = "f(g(x2, x<caret>1), y)\n",
    ) {
        list("g(x1, x2)", "y")
        list("x1", "x2")
    }

    /** On the callee of a nested call, the caret is outside its arguments and in the outer list. */
    @Test
    fun `the callee of a nested call moves with its call`() = move(
        right = true,
        before = "f(<caret>g(x1, x2), y)\n",
        after = "f(y, <caret>g(x1, x2))\n",
    ) {
        list("g(x1, x2)", "y")
        list("x1", "x2")
    }

    @Test
    fun `the last element has nowhere to go right`() = move(
        right = true,
        before = "f(alpha, be<caret>ta)\n",
        after = "f(alpha, be<caret>ta)\n",
    ) { list("alpha", "beta") }

    /** Before `by` has answered for the text on screen, nothing moves rather than a guess. */
    @Test
    fun `nothing moves without an outline`() = move(
        right = true,
        before = "f(al<caret>pha, beta)\n",
        after = "f(al<caret>pha, beta)\n",
        outline = null,
    )
}
