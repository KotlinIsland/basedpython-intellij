package dev.basedpython.pycharm.editor.scope

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.EditorTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `the action is enabled in a by editor only, and put ahead of the platform's on the shortcut`() = onEdt {
        val action = ActionManager.getInstance().getAction("basedpython.ContextInfo")
        assertTrue(action is ByContextInfoAction)
        val platform = ActionManager.getInstance().getAction("EditorContextInfo")
        fun enabledIn(name: String, text: String): Boolean {
            fixture.configureByText(name, text)
            val event = AnActionEvent.createEvent(
                action, DataManager.getInstance().getDataContext(fixture.editor.contentComponent), null,
                ActionPlaces.KEYBOARD_SHORTCUT, ActionUiKind.NONE, null,
            )
            ActionUtil.updateAction(action, event)
            return event.presentation.isEnabled
        }
        assertTrue(enabledIn("c.by", "x = 1\n"))
        assertFalse(enabledIn("c.txt", "x = 1\n"))

        val promoter = ExtensionPointName<ActionPromoter>("com.intellij.actionPromoter").extensionList
            .single { it is ByContextInfoPromoter }
        assertEquals(listOf(action), promoter.promote(listOf(platform, action), DataManager.getInstance().getDataContext(fixture.editor.contentComponent)))
    }
}
