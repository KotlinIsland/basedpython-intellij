package dev.basedpython.pycharm.settings.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [BasedPythonDefaults] resolution. These exercise the
 * explicit-default overloads, so no application service is required.
 */
class BasedPythonDefaultsTest {

    // --- byPath -------------------------------------------------------------

    @Test
    fun `byPath project value wins over default`() {
        assertEquals("/proj/by", BasedPythonDefaults.effectiveByPath("/proj/by", "/app/by"))
    }

    @Test
    fun `byPath null project falls back to default`() {
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath(null, "/app/by"))
    }

    @Test
    fun `byPath blank project falls back to default`() {
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath("   ", "/app/by"))
    }

    @Test
    fun `byPath empty project falls back to default`() {
        assertEquals("/app/by", BasedPythonDefaults.effectiveByPath("", "/app/by"))
    }

    @Test
    fun `byPath both null yields null`() {
        assertNull(BasedPythonDefaults.effectiveByPath(null, null))
    }

    @Test
    fun `byPath project set but default null still wins`() {
        assertEquals("/proj/by", BasedPythonDefaults.effectiveByPath("/proj/by", null))
    }

    // --- buffPath -----------------------------------------------------------

    @Test
    fun `buffPath project value wins over default`() {
        assertEquals("/proj/buff", BasedPythonDefaults.effectiveBuffPath("/proj/buff", "/app/buff"))
    }

    @Test
    fun `buffPath blank project falls back to default`() {
        assertEquals("/app/buff", BasedPythonDefaults.effectiveBuffPath(" ", "/app/buff"))
    }

    @Test
    fun `buffPath both null yields null`() {
        assertNull(BasedPythonDefaults.effectiveBuffPath(null, null))
    }

    // --- extra args ---------------------------------------------------------

    @Test
    fun `extraArgs project value wins over default`() {
        assertEquals("--proj", BasedPythonDefaults.effectiveExtraArgs("--proj", "--app"))
    }

    @Test
    fun `extraArgs blank project falls back to default`() {
        assertEquals("--app", BasedPythonDefaults.effectiveExtraArgs("", "--app"))
    }

    @Test
    fun `extraArgs null project falls back to default`() {
        assertEquals("--app", BasedPythonDefaults.effectiveExtraArgs(null, "--app"))
    }

    @Test
    fun `extraArgs both empty yields empty`() {
        assertEquals("", BasedPythonDefaults.effectiveExtraArgs("", ""))
    }

    // --- server toggles -----------------------------------------------------

    @Test
    fun `enabled project choice wins over default either way`() {
        assertEquals(false, BasedPythonDefaults.effectiveEnabled(false, true))
        assertEquals(true, BasedPythonDefaults.effectiveEnabled(true, false))
    }

    @Test
    fun `enabled unchosen follows default either way`() {
        assertEquals(true, BasedPythonDefaults.effectiveEnabled(null, true))
        assertEquals(false, BasedPythonDefaults.effectiveEnabled(null, false))
    }
}
