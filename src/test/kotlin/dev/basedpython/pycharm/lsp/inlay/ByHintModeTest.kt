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
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("never", legacyEnabled = true))
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("always", legacyEnabled = false))
        assertEquals(ByHintMode.ON_PUSH, ByHintMode.resolve("push", legacyEnabled = false))
    }

    @Test
    fun `no mode falls back to the boolean that came before it`() {
        // A project configured before push-to-hint existed keeps exactly the hints it had.
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("", legacyEnabled = true))
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("", legacyEnabled = false))
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve(null, legacyEnabled = true))
    }

    @Test
    fun `an unknown mode degrades to the boolean rather than throwing`() {
        // A settings file written by a newer plugin has to load, not fail.
        assertEquals(ByHintMode.ALWAYS, ByHintMode.resolve("on-hover", legacyEnabled = true))
        assertEquals(ByHintMode.NEVER, ByHintMode.resolve("on-hover", legacyEnabled = false))
    }

    @Test
    fun `the persisted ids are the ones the settings format promises`() {
        assertEquals(listOf("never", "always", "push"), ByHintMode.entries.map { it.id })
    }

    // endregion
}
