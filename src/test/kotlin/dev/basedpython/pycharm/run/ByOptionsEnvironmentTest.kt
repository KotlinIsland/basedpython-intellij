package dev.basedpython.pycharm.run

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.basedpython.pycharm.env.ByEnvironmentKind
import junit.framework.TestCase

/**
 * Persistence contract for [ByCommonOptions.environment] / [ByCommonOptions.environmentKind].
 *
 * The serializer round-trips the *string* and converts with a strict lookup, so an enum-typed
 * persisted property would store the constant name and throw on anything it cannot match. These
 * tests pin the string form and the degradation behaviour that keeps a shared run configuration
 * loadable across plugin versions.
 */
class ByOptionsEnvironmentTest : TestCase() {

    fun `test the default is AUTO and persists as blank`() {
        val o = ByCommonOptions()
        assertEquals(ByEnvironmentKind.AUTO, o.environmentKind)
        // Blank matches the property default, so an untouched configuration writes no option line.
        assertEquals("", o.environment)
    }

    fun `test setting AUTO explicitly still persists as blank`() {
        // Otherwise opening and OK-ing any existing run config would dirty VCS-tracked XML.
        val o = ByCommonOptions()
        o.environmentKind = ByEnvironmentKind.UV
        o.environmentKind = ByEnvironmentKind.AUTO
        assertEquals("", o.environment)
    }

    fun `test each kind round-trips through the persisted string`() {
        for (kind in ByEnvironmentKind.entries) {
            val o = ByCommonOptions()
            o.environmentKind = kind
            assertEquals("round-trip failed for $kind", kind, o.environmentKind)
        }
    }

    fun `test the persisted form is the stable id not the enum constant name`() {
        val o = ByCommonOptions()
        o.environmentKind = ByEnvironmentKind.UV
        assertEquals("uv", o.environment)
    }

    fun `test an unknown persisted value degrades to AUTO instead of throwing`() {
        val o = ByCommonOptions()
        o.environment = "conda"
        assertEquals(ByEnvironmentKind.AUTO, o.environmentKind)
    }

    fun `test a legacy configuration with no value deserialises to AUTO`() {
        val o = ByCommonOptions()
        o.environment = ""
        assertEquals(ByEnvironmentKind.AUTO, o.environmentKind)
    }

    fun `test subclasses inherit the environment option`() {
        for (o in listOf(ByRunOptions(), ByBuildOptions(), ByCheckOptions())) {
            o.environmentKind = ByEnvironmentKind.VENV
            assertEquals(ByEnvironmentKind.VENV, o.environmentKind)
            assertEquals("venv", o.environment)
        }
    }

    // --- real serialisation (what actually lands in the run configuration XML) ---

    private fun serialize(o: ByCommonOptions): String =
        JDOMUtil.write(XmlSerializer.serialize(o))

    fun `test the XML stores the id and not the enum constant name`() {
        val o = ByCommonOptions()
        o.environmentKind = ByEnvironmentKind.UV
        val xml = serialize(o)
        assertTrue("expected the stable id in $xml", xml.contains("value=\"uv\""))
        assertFalse("the enum constant name must not be persisted: $xml", xml.contains("value=\"UV\""))
    }

    fun `test the derived kind property is not serialised`() {
        // Two writable views of one value; only `environment` may reach the XML, or the file would
        // carry a second, conflicting copy.
        val o = ByCommonOptions()
        o.environmentKind = ByEnvironmentKind.UV
        assertFalse("environmentKind must be @Transient: ${serialize(o)}", serialize(o).contains("environmentKind"))
    }

    fun `test an untouched configuration writes no environment option`() {
        assertFalse(
            "AUTO is the default and must not add an option line",
            serialize(ByCommonOptions()).contains("environment"),
        )
    }
}
