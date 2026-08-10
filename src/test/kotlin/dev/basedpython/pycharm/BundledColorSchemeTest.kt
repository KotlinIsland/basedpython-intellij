package dev.basedpython.pycharm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `bundledColorScheme` extension point takes a resource path *without* an extension and
 * appends `.xml` itself. A scheme saved with the IDE's export extension (`.icls`) therefore
 * resolves to nothing, and the platform fails it at app startup — inside
 * `EditorColorsManagerImpl.<init>`, as a `PluginException` with a null message that names the
 * plugin but not the missing file.
 *
 * Nothing else in the build checks that these paths resolve: the schemes are plain resources, so
 * a wrong extension compiles, packages, and passes the plugin verifier. This test is the check.
 */
class BundledColorSchemeTest {

    private val pluginXml: String =
        checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml missing from the test classpath"
        }.use { it.readBytes().decodeToString() }

    /** Every `path` declared by a `bundledColorScheme` entry in plugin.xml. */
    private fun declaredSchemePaths(): List<String> =
        Regex("""<bundledColorScheme\s+path="([^"]+)"""")
            .findAll(pluginXml)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `plugin declares both bundled schemes`() {
        assertEquals(
            listOf("/colorSchemes/BasedPythonDark", "/colorSchemes/BasedPythonLight"),
            declaredSchemePaths(),
        )
    }

    @Test
    fun `every declared scheme resolves to an xml resource`() {
        for (path in declaredSchemePaths()) {
            val resource = "$path.xml"
            assertNotNull(
                javaClass.getResource(resource),
                "bundledColorScheme path=\"$path\" resolves to no resource. The platform appends " +
                    "\".xml\"; a file named \"$path.icls\" will not be found.",
            )
        }
    }

    @Test
    fun `declared schemes are full scheme files, not additionalTextAttributes lists`() {
        // `bundledColorScheme` wants a <scheme> document. `<list>` is the additionalTextAttributes
        // format and would be silently useless here.
        for (path in declaredSchemePaths()) {
            val body = checkNotNull(javaClass.getResourceAsStream("$path.xml")).use {
                it.readBytes().decodeToString()
            }
            assertTrue(
                body.contains("<scheme "),
                "$path.xml must be a <scheme> document, but starts with: ${body.take(60)}",
            )
            assertTrue(
                body.contains("parent_scheme="),
                "$path.xml must declare a parent_scheme",
            )
        }
    }

    /**
     * A parent_scheme is resolved by name at startup, and an unresolvable one throws
     * InvalidDataException out of AbstractColorsScheme.resolveParent, taking the plugin's schemes
     * down with it. Only these base schemes ship with every IDE.
     *
     * "Islands Dark" looks available but is not: it is tagged `ide=idea`, so PyCharm reports
     * "Theme Islands Dark refers to unknown color scheme Islands Dark" for its own theme. To colour
     * an optional scheme like that, use `additionalTextAttributes scheme="..."`, which is ignored
     * when the scheme is absent, rather than inheriting from it.
     */
    @Test
    fun `parent scheme is one that ships with every IDE`() {
        val universal = setOf("Darcula", "Default", "High contrast")
        for (path in declaredSchemePaths()) {
            val body = checkNotNull(javaClass.getResourceAsStream("$path.xml")).use {
                it.readBytes().decodeToString()
            }
            val parent = Regex("""parent_scheme="([^"]+)"""").find(body)?.groupValues?.get(1)
            assertTrue(
                parent in universal,
                "$path.xml has parent_scheme=\"$parent\", which is not guaranteed to exist in " +
                    "every IDE. Allowed: $universal",
            )
        }
    }
}
