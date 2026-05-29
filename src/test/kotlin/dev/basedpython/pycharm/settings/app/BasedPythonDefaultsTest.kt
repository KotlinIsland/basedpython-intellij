package dev.basedpython.pycharm.settings.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    // --- python version -----------------------------------------------------

    @Test
    fun `pythonVersion project value wins`() {
        assertEquals("3.13", BasedPythonDefaults.effectivePythonVersion("3.13", "3.10"))
    }

    @Test
    fun `pythonVersion blank falls back to default`() {
        assertEquals("3.11", BasedPythonDefaults.effectivePythonVersion("  ", "3.11"))
    }

    @Test
    fun `pythonVersion null falls back to default`() {
        assertEquals("3.10", BasedPythonDefaults.effectivePythonVersion(null, "3.10"))
    }

    // --- lsp trace level ----------------------------------------------------

    @Test
    fun `lspTrace project value wins`() {
        assertEquals("verbose", BasedPythonDefaults.effectiveLspTraceLevel("verbose", "off"))
    }

    @Test
    fun `lspTrace blank falls back to default`() {
        assertEquals("messages", BasedPythonDefaults.effectiveLspTraceLevel("", "messages"))
    }

    @Test
    fun `lspTrace null falls back to default`() {
        assertEquals("off", BasedPythonDefaults.effectiveLspTraceLevel(null, "off"))
    }
}
