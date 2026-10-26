package dev.basedpython.pycharm.debug.logpoint

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *Add Log Point* and the `Log:` box in an IDE that ships its own log points — IntelliJ IDEA, and
 * PyCharm since 263.7355 — whose *Add Logpoint* and box serve only breakpoint types that support
 * inter-line placement, which `basedpython-line` cannot claim.
 *
 * Both used to stand down wherever the IDE shipped log points at all, so in PyCharm 263.7355 a `.by`
 * caret had neither action enabled, and a `.by` log point had no box from either side.
 */
@TestFixtures
class ByAddLogpointActionTest {

    private val fixture by codeInsightFixture()

    /**
     * The test IDE is IntelliJ IDEA with its Java plugin, so the IDE's own log points are really
     * here — `AddLogpoint` is `ToggleLogpointAction`. [body] runs with it replaced by one that is
     * enabled for every event, for the case where the IDE's would act.
     */
    private fun withIdeAddLogpointEnabled(body: () -> Unit) {
        val manager = ActionManager.getInstance()
        val ide = manager.getAction(IDE_ACTION)
        assertNotNull(ide, "the test IDE ships IntelliJ IDEA's log points")
        manager.replaceAction(IDE_ACTION, object : AnAction() {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = true
            }

            override fun actionPerformed(e: AnActionEvent) = Unit
        })
        try {
            body()
        } finally {
            manager.replaceAction(IDE_ACTION, ide!!)
        }
    }

    private fun enabled(id: String): Boolean {
        val action = ActionManager.getInstance().getAction(id)!!
        val context = DataManager.getInstance().getDataContext(fixture.editor.contentComponent)
        val event = AnActionEvent.createEvent(
            context,
            action.templatePresentation.clone(),
            ActionPlaces.KEYBOARD_SHORTCUT,
            ActionUiKind.NONE,
            null,
        )
        ActionUtil.updateAction(action, event)
        return event.presentation.isEnabledAndVisible
    }

    @Test
    fun `Add Log Point takes the keystroke where the IDE's Add Logpoint does not apply`() = onEdt {
        fixture.configureByText("main.by", "def f(x):\n    return <caret>x\n")

        assertFalse(enabled(IDE_ACTION), "the IDE's toggles only types that support inter-line placement")
        assertTrue(enabled(OWN_ACTION), "nothing else would act on Ctrl+Alt+F8 here")
    }

    @Test
    fun `Add Log Point gives way where the IDE's Add Logpoint applies`() = onEdt {
        fixture.configureByText("main.by", "def f(x):\n    return <caret>x\n")
        withIdeAddLogpointEnabled {
            assertFalse(enabled(OWN_ACTION), "two enabled actions on one shortcut is a coin flip")
        }
    }

    @Test
    fun `a by log point gets its box in an IDE that ships log points`() = onEdt {
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!
        val logpoint = XDebuggerManager.getInstance(fixture.project).breakpointManager.addLineBreakpoint(
            type,
            fixture.file.virtualFile.url,
            1,
            null,
            PlatformLogpointInfo.of(SuspendPolicy.NONE, ByLogpoints.expressionOf("x")),
        )

        assertNotNull(
            ByLogpointField.of(fixture.editor as EditorEx, logpoint),
            "the IDE's box never applies to a .by breakpoint, so this is the only one there is",
        )
    }

    private companion object {
        const val IDE_ACTION = "AddLogpoint"
        const val OWN_ACTION = "basedpython.AddLogpoint"
    }
}
