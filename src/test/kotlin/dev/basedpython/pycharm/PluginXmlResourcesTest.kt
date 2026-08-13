package dev.basedpython.pycharm

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        assertTrue(declared.isNotEmpty(), "expected live templates to be declared")
        for (path in declared) {
            assertNotNull(
                javaClass.getResource("/$path.xml"),
                "defaultLiveTemplates file=\"$path\" resolves to no resource; the platform appends " +
                    "\".xml\" and the lookup is case-sensitive",
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
        assertTrue(classNames.isNotEmpty(), "expected intention actions to be declared")
        val missing = classNames.filter {
            javaClass.getResource("/intentionDescriptions/$it/description.html") == null
        }
        assertTrue(
            missing.isEmpty(),
            "intentions with no intentionDescriptions/<ClassName>/description.html: $missing",
        )
    }

    /**
     * A tool window is two names in a string: the factory class the platform instantiates, and the
     * icon it looks up reflectively (`AllIcons.Nodes.JunitTestMark` is read as a field of a class,
     * not resolved by the compiler). Both fail at runtime, when the user clicks the stripe button.
     */
    @Test
    fun `every tool window factory and icon resolves`() {
        val factories = attr("toolWindow", "factoryClass")
        assertTrue(factories.isNotEmpty(), "expected tool windows to be declared")
        for (className in factories) {
            assertNotNull(
                runCatching { Class.forName(className) }.getOrNull(),
                "toolWindow factoryClass=\"$className\" names no class",
            )
        }
        for (reference in attr("toolWindow", "icon")) {
            val field = reference.substringAfterLast('.')
            val owner = "com.intellij.icons." + reference.dropLast(field.length + 1).replace('.', '$')
            assertNotNull(
                runCatching { Class.forName(owner).getField(field).get(null) }.getOrNull(),
                "toolWindow icon=\"$reference\" resolves to no icon field",
            )
        }
    }
}
