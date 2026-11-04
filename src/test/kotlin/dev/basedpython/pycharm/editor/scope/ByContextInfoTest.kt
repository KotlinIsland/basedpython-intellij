package dev.basedpython.pycharm.editor.scope

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * View | Context Info in a `.by` editor: the scopes around the caret read off `by`'s outline, which
 * of them the action shows, and the action standing in for the platform's on its shortcut there.
 */
@TestFixtures
class ByContextInfoTest {

    private val fixture by codeInsightFixture()

    private val body = (1..40).joinToString("") { "        line$it = $it\n" }

    private val source = """
        |class Circle(Shape):
        |    @property
        |    def area(
        |        self,
        |    ) -> float:
        |        if self.r:
        |            pass
        |        else:
        |            total = 0
        |""".trimMargin() + body

    private val outline: OutlineSpec.Suite.() -> Unit = {
        compound(name = "Circle") {
            clause("class Circle(Shape):") {
                compound(decorators = "@property", name = "area") {
                    clause("def area(\n        self,\n    ) -> float:") {
                        compound {
                            clause("if self.r:") { simple("pass") }
                            clause("else:") { simple("total = 0") }
                        }
                        for (i in 1..40) simple("line$i = $i")
                    }
                }
            }
        }
    }

    private fun text(range: TextRange?): String? = range?.substring(fixture.editor.document.text)

    @Test
    fun `the scopes around an offset are innermost first, each with its header, decorators included`() = onEdt {
        fixture.configureByText("a.by", source)
        val outline = OutlineSpec.outline(fixture.editor.document, outline)
        val scopes = ByEnclosingScopes.at(outline, source.indexOf("total"))
        assertEquals(
            listOf("else" to false, "@property\n    def area(\n        self,\n    ) -> float" to true, "class Circle(Shape)" to true),
            scopes.map { text(it.header) to it.isDefinition },
        )
        assertEquals(
            listOf("@property\n    def area(\n        self,\n    ) -> float", "class Circle(Shape)"),
            ByEnclosingScopes.at(outline, source.indexOf("@property") + 1).map { text(it.header) },
            "on a decorator: in the def it decorates",
        )
        assertEquals(emptyList<ByEnclosingScope>(), ByEnclosingScopes.at(outline, source.length + 1), "outside every statement")
    }

    @Test
    fun `the innermost definition whose header is out of sight is shown, and asked again the next one out`() {
        val headers = listOf(TextRange(30, 40), TextRange(20, 25), TextRange(0, 10))
        val outOfSight = setOf(TextRange(20, 25), TextRange(0, 10))
        assertEquals(TextRange(20, 25), ByContextInfo.choose(headers, null) { it !in outOfSight }, "the innermost in sight is passed over")
        assertEquals(TextRange(0, 10), ByContextInfo.choose(headers, 20) { it !in outOfSight }, "again: the one out from the one shown")
        assertNull(ByContextInfo.choose(headers, 0) { it !in outOfSight }, "again on the outermost: nothing")
        assertNull(ByContextInfo.choose(headers, null) { true }, "every header in sight: nothing")
        assertEquals(TextRange(20, 25), ByContextInfo.choose(headers, 99) { it !in outOfSight }, "a shown one no longer around the caret: as if not asked before")
    }

    @Test
    fun `scrolled past the def, the header shown is the def's, then the class's`() = onEdt {
        fixture.configureByText("b.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        val editor = fixture.editor
        EditorTestUtil.setEditorVisibleSize(editor, 80, 10)
        editor.caretModel.moveToLogicalPosition(LogicalPosition(40, 8))
        assertNull(ByContextInfo.getInstance().headerToShow(editor), "both headers in sight at the top of the file")

        editor.scrollingModel.disableAnimation()
        editor.scrollingModel.scrollVertically(editor.logicalPositionToXY(LogicalPosition(30, 0)).y)
        val def = ByContextInfo.getInstance().headerToShow(editor)
        assertEquals("@property\n    def area(\n        self,\n    ) -> float", text(def))
        assertEquals("class Circle(Shape)", text(ByContextInfo.getInstance().headerToShow(editor, def!!.startOffset)))
    }

    @Test
    fun `the platform's action is wrapped in place, in its menu, acting in a by editor and handing anything else to the platform's`() = onEdt {
        val actions = ActionManager.getInstance()
        val install = service<ByContextInfoInstall>()
        install.install()
        val action = actions.getAction(ByContextInfoAction.ID)
        assertTrue(action is ByContextInfoAction)
        val platform = (action as ByContextInfoAction).delegate
        assertFalse(platform is ByContextInfoAction)
        // View | Context Info is the platform's reference to it in this group of the View menu, which
        // the menu resolves by id
        val viewMenu = actions.getAction("CodeEditorViewGroup") as DefaultActionGroup
        assertTrue(viewMenu.getChildren(actions).any { it === action }, "the View menu's item is the wrapper")
        fun updated(name: String, text: String): AnActionEvent {
            fixture.configureByText(name, text)
            val event = AnActionEvent.createEvent(
                action, DataManager.getInstance().getDataContext(fixture.editor.contentComponent), null,
                ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null,
            )
            ActionUtil.updateAction(action, event)
            return event
        }
        assertTrue(updated("c.by", "x = 1\n").presentation.isEnabledAndVisible)
        // a plain text file has no structure, and the platform's action says so itself
        val plain = updated("c.txt", "x = 1\n").presentation
        val direct = AnActionEvent.createEvent(
            platform, DataManager.getInstance().getDataContext(fixture.editor.contentComponent), null,
            ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null,
        ).also { ActionUtil.updateAction(platform, it) }.presentation
        assertEquals(direct.isEnabled to direct.isVisible, plain.isEnabled to plain.isVisible)

        install.dispose()
        assertSame(platform, actions.getAction(ByContextInfoAction.ID), "unloading puts the platform's back")
        assertTrue(viewMenu.getChildren(actions).any { it === platform })
        install.install()
    }
}
