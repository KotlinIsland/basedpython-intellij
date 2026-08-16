package dev.basedpython.pycharm.tasks

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
 * The task view's Swing side under a real platform: that the panel builds, that its toolbar carries
 * actions which survive an update with nothing selected, and that a project with no hook
 * configuration shows an empty tree rather than failing to open.
 *
 * What a configuration file turns into is [PreCommitTasksTest]'s and [LefthookTasksTest]'s subject;
 * this is the wiring.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTaskPanelTest {

    private val fixture by codeInsightFixture()

    private fun <T> withPanel(body: (ByTaskPanel) -> T): T {
        val panel = ByTaskPanel(fixture.project)
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
    fun `a project with no hook configuration shows an empty tree`() {
        withPanel { panel ->
            val tree = checkNotNull(find(panel, Tree::class.java))
            assertEquals(0, tree.model.getChildCount(tree.model.root))
            assertTrue(ByTaskService.getInstance(fixture.project).files.isEmpty())
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

    @Test
    fun `the gear menu offers the all-files toggle`() {
        withPanel { panel ->
            assertTrue(panel.gearActions().childrenCount > 0)
        }
    }
}
