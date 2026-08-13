package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel

/**
 * [ByHintPush] read the way it is fed in production: whole AWT events, played at the method the
 * event dispatcher calls.
 *
 * All this needs is an application, the service being one, and the fixture is how the rest of these
 * tests get a real one — `@TestApplication` on its own would tear the application down at the end
 * of this class and take the light project the other fixtures are parked in with it. Nothing here
 * touches the event queue: what is under test is the reading of an event, not the platform's
 * delivery of it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByHintPushTest {

    private val fixture by codeInsightFixture()

    private val push get() = ByHintPush.getInstance()

    /** A watcher has to name an editor, so that one editor's updates can be batched. */
    private fun editor(): Editor {
        fixture.configureByText("a.txt", "value = 1")
        return fixture.editor
    }

    private val source = JPanel()

    /** A key event carrying [modifiersEx], which is the only part of it this reads. */
    private fun keyEvent(modifiersEx: Int, keyCode: Int = KeyEvent.VK_CONTROL): KeyEvent =
        KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiersEx, keyCode, KeyEvent.CHAR_UNDEFINED)

    /** The service is the application's, and so is shared with whatever ran before this. */
    @BeforeEach
    fun releaseEverything() {
        push.onEvent(keyEvent(0))
    }

    private class CountingWatcher(override val editor: Editor) : ByHintPush.Watcher {
        var changes = 0
        override fun pushStateChanged() {
            changes++
        }
    }

    @Test
    fun `a key event carrying the modifiers is the push going down and coming up`() {
        push.onEvent(keyEvent(InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK))
        assertTrue(push.isHeld(ByPushKey.CTRL_ALT))
        assertTrue(push.isHeld(ByPushKey.CTRL))
        assertFalse(push.isHeld(ByPushKey.META))

        push.onEvent(keyEvent(0))
        assertFalse(push.isHeld(ByPushKey.CTRL_ALT))
    }

    @Test
    fun `watchers hear each change once, and nothing when the state repeats`() {
        val watcher = CountingWatcher(editor())
        push.watch(watcher)

        push.onEvent(keyEvent(InputEvent.CTRL_DOWN_MASK))
        push.onEvent(keyEvent(InputEvent.CTRL_DOWN_MASK, keyCode = KeyEvent.VK_A))
        assertEquals(1, watcher.changes, "the same modifiers held on is not a change")

        push.onEvent(keyEvent(0))
        assertEquals(2, watcher.changes)
    }

    @Test
    fun `modifiers this cannot be asked about are not a change`() {
        val watcher = CountingWatcher(editor())
        push.watch(watcher)

        push.onEvent(keyEvent(InputEvent.ALT_GRAPH_DOWN_MASK))
        assertEquals(0, watcher.changes)
    }

    // The remaining branch — a `WINDOW_DEACTIVATED` clearing the state — is not testable here:
    // building the event needs a real `java.awt.Window`, and these tests run headless.
}
