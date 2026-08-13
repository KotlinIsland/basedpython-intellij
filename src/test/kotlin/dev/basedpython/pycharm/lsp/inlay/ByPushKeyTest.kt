package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent

/**
 * [ByPushKey] against raw [InputEvent.getModifiersEx] masks — the reading [ByHintPush] does of every
 * event, with no event queue anywhere near it.
 */
class ByPushKeyTest {

    private val ctrl = InputEvent.CTRL_DOWN_MASK
    private val alt = InputEvent.ALT_DOWN_MASK
    private val shift = InputEvent.SHIFT_DOWN_MASK

    @Test
    fun `nothing held is nothing pushed`() {
        for (key in ByPushKey.entries) {
            assertFalse(key.isHeldIn(0), "$key should not be held in an empty mask")
        }
    }

    @Test
    fun `a single modifier answers for itself alone`() {
        assertTrue(ByPushKey.CTRL.isHeldIn(ctrl))
        assertFalse(ByPushKey.ALT.isHeldIn(ctrl))
        assertFalse(ByPushKey.CTRL_ALT.isHeldIn(ctrl))
    }

    @Test
    fun `a combination needs every modifier it names`() {
        assertFalse(ByPushKey.CTRL_ALT.isHeldIn(alt))
        assertTrue(ByPushKey.CTRL_ALT.isHeldIn(ctrl or alt))
    }

    @Test
    fun `extra modifiers do not break the hold`() {
        // Holding Ctrl+Alt and reaching for Shift to select keeps the hints up.
        assertTrue(ByPushKey.CTRL_ALT.isHeldIn(ctrl or alt or shift))
        assertTrue(ByPushKey.CTRL.isHeldIn(ctrl or alt))
    }

    @Test
    fun `the watched mask covers every key on offer`() {
        for (key in ByPushKey.entries) {
            assertTrue(
                key.isHeldIn(ByPushKey.WATCHED_MODIFIERS),
                "$key is not covered by the modifiers ByHintPush tracks",
            )
        }
    }

    @Test
    fun `an unknown id degrades to the default rather than throwing`() {
        assertEquals(ByPushKey.CTRL_ALT, ByPushKey.fromId("hyper"))
        assertEquals(ByPushKey.CTRL_ALT, ByPushKey.fromId(""))
        assertEquals(ByPushKey.CTRL_ALT, ByPushKey.fromId(null))
        assertEquals(ByPushKey.SHIFT, ByPushKey.fromId("shift"))
    }

    @Test
    fun `the persisted ids are the ones the settings format promises`() {
        assertEquals(
            listOf("ctrl", "alt", "shift", "ctrl-alt", "ctrl-shift", "meta"),
            ByPushKey.entries.map { it.id },
        )
    }
}
