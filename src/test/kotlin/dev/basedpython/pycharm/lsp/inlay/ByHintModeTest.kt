package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ByHintMode] as the two tables it is: when a mode draws, and what a settings file means.
 */
class ByHintModeTest {

    // region: when a mode draws

    @Test
    fun `never draws under neither state`() {
        assertFalse(ByHintMode.NEVER.isShown(pushed = false))
        assertFalse(ByHintMode.NEVER.isShown(pushed = true))
    }

    @Test
    fun `always draws under both`() {
        assertTrue(ByHintMode.ALWAYS.isShown(pushed = false))
        assertTrue(ByHintMode.ALWAYS.isShown(pushed = true))
    }

    @Test
    fun `on push draws only while the key is held`() {
        assertFalse(ByHintMode.ON_PUSH.isShown(pushed = false))
        assertTrue(ByHintMode.ON_PUSH.isShown(pushed = true))
    }

    @Test
    fun `a push-mode hint is collected even while it is not drawn`() {
        // What makes the peek instant: the inlay is already there, drawing nothing.
        assertTrue(ByHintMode.ON_PUSH.isCollected)
        assertTrue(ByHintMode.ALWAYS.isCollected)
        assertFalse(ByHintMode.NEVER.isCollected)
    }

    // endregion

    // region: what a settings file means

    @Test
    fun `a written mode is what it says`() {
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("never", ByHintMode.ALWAYS))
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("always", ByHintMode.NEVER))
        assertEquals(ByHintMode.ON_PUSH, ByHintMode.resolve("push", ByHintMode.NEVER))
    }

    @Test
    fun `no mode is whatever it falls back to`() {
        // For the three original kinds that is the boolean toggle they were configured with, and
        // for the three that used to travel with variable types it is the mode variable types are
        // on — either way, a project configured before this keeps exactly the hints it had.
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("", ByHintMode.of(true)))
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("", ByHintMode.of(false)))
        assertEquals(ByHintMode.ON_PUSH, ByHintMode.resolve(null, ByHintMode.ON_PUSH))
    }

    @Test
    fun `an unknown mode degrades to the fallback rather than throwing`() {
        // A settings file written by a newer plugin has to load, not fail.
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("on-hover", ByHintMode.ALWAYS))
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("on-hover", ByHintMode.NEVER))
    }

    @Test
    fun `the boolean that came before means always or never`() {
        assertEquals(ByHintMode.ALWAYS, ByHintMode.of(true))
        assertEquals(ByHintMode.NEVER, ByHintMode.of(false))
    }

    @Test
    fun `the persisted ids are the ones the settings format promises`() {
        assertEquals(listOf("never", "always", "push"), ByHintMode.entries.map { it.id })
    }

    // endregion
}
