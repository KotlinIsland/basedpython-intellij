package dev.basedpython.pycharm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plugin.xml points at resources by path, and nothing in the build checks those paths resolve — a
 * wrong one compiles, packages, and passes the plugin verifier, then throws a PluginException at
 * runtime naming only the missing string. Three separate bugs of exactly this shape shipped
 * (`.icls` schemes, `liveTemplates/basedpython`, a missing intention description), so the
 * declarations are checked here instead of in the IDE log.
 */
class PluginXmlResourcesTest {

    private val pluginXml: String =
        checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")) {
            "plugin.xml missing from the test classpath"
        }.use { it.readBytes().decodeToString() }

    private fun attr(tag: String, attr: String): List<String> =
        Regex("""<$tag\s+[^>]*$attr="([^"]+)"""")
            .findAll(pluginXml)
            .map { it.groupValues[1] }
            .toList()

    /**
     * `defaultLiveTemplates` takes an extensionless path and appends `.xml`. Case matters inside a
     * jar, so a lowercase reference to `BasedPython.xml` resolves to nothing on every platform.
     */
    @Test
    fun `every declared live template file resolves`() {
        val declared = attr("defaultLiveTemplates", "file")
        assertTrue("expected live templates to be declared", declared.isNotEmpty())
        for (path in declared) {
            assertNotNull(
                "defaultLiveTemplates file=\"$path\" resolves to no resource; the platform appends " +
                    "\".xml\" and the lookup is case-sensitive",
                javaClass.getResource("/$path.xml"),
            )
        }
    }

    /**
     * The platform locates an intention's description by the *simple class name* of its
     * implementation, so a renamed or newly added intention silently loses its description.
     */
    @Test
    fun `every intention action has a description`() {
        val classNames = Regex("""<className>([^<]+)</className>""")
            .findAll(pluginXml)
            .map { it.groupValues[1].substringAfterLast('.') }
            .toList()
        assertTrue("expected intention actions to be declared", classNames.isNotEmpty())
        val missing = classNames.filter {
            javaClass.getResource("/intentionDescriptions/$it/description.html") == null
        }
        assertTrue(
            "intentions with no intentionDescriptions/<ClassName>/description.html: $missing",
            missing.isEmpty(),
        )
    }
}
