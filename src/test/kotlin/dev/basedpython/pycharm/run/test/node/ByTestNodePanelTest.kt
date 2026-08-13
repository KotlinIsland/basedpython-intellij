package dev.basedpython.pycharm.run.test.node

import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.treeStructure.Tree
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import javax.swing.JComponent

/**
 * The node view's Swing side, under a real platform: that the panel builds, that its toolbar
 * carries actions that survive an update, and that the tree it wraps is there to be filled.
 *
 * Nothing here runs `by`. A panel starts on [ByTestNodeService.State.Idle] and collects only when
 * something asks it to, which is the tool window factory's job and not the constructor's — so this
 * stays a test of the wiring, while what a collection turns into is [ByTestNodesTest]'s subject.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTestNodePanelTest {

    private val fixture by codeInsightFixture()

    private fun <T> withPanel(body: (ByTestNodePanel) -> T): T {
        val panel = ByTestNodePanel(fixture.project)
        try {
            return body(panel)
        } finally {
            Disposer.dispose(panel)
        }
    }

    /** Depth-first search of [root]'s descendants for the first component of type [type]. */
    private fun <T : JComponent> find(root: Container, type: Class<T>): T? {
        for (component in root.components) {
            if (type.isInstance(component)) return type.cast(component)
            if (component is Container) find(component, type)?.let { return it }
        }
        return null
    }

    @Test
    fun `the panel builds a tree and a toolbar`() {
        withPanel { panel ->
            assertNotNull(find(panel, Tree::class.java), "the panel should contain a tree")
            assertTrue(panel.toolbar is ActionToolbarImpl, "the panel should have an action toolbar")
        }
    }

    @Test
    fun `an uncollected project shows an empty tree, having run nothing`() {
        withPanel { panel ->
            val tree = checkNotNull(find(panel, Tree::class.java))
            assertEquals(0, tree.model.getChildCount(tree.model.root))
            assertEquals(ByTestNodeService.State.Idle, ByTestNodeService.getInstance(fixture.project).state)
        }
    }

    /**
     * Every action is asked whether it applies with nothing selected — the state the view opens in,
     * and the one where `update` has no node to read.
     */
    @Test
    fun `every toolbar action updates with no selection`() {
        withPanel { panel ->
            val toolbar = panel.toolbar as ActionToolbarImpl
            // The toolbar holds a group, not a list, until it is asked to present it.
            toolbar.updateActionsImmediately()
            val actions = toolbar.actions.filter { it.templateText != null }
            assertTrue(actions.size >= 5, "expected the toolbar's actions, got ${actions.map { it.templateText }}")
            val context = SimpleDataContext.getProjectContext(fixture.project)
            for (action in actions) {
                val event = AnActionEvent.createEvent(
                    context,
                    action.templatePresentation.clone(),
                    "test",
                    ActionUiKind.TOOLBAR,
                    null,
                )
                action.update(event)
            }
        }
    }
}
