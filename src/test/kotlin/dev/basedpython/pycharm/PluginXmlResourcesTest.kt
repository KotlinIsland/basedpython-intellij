package dev.basedpython.pycharm

import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
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
     * Names of the resources directly under [dir]. The test sandbox packages resources into a jar,
     * where the directory is not a [java.io.File] — hence the zip filesystem, which is shared, so
     * it is only closed if this call is the one that opened it.
     */
    private fun listResource(dir: String): List<String> {
        val uri = checkNotNull(javaClass.getResource(dir)) {
            "$dir missing from the test classpath"
        }.toURI()
        if (uri.scheme != "jar") return java.io.File(uri).list().orEmpty().toList()
        val opened = try {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        } catch (_: FileSystemAlreadyExistsException) {
            null
        }
        try {
            val fs = opened ?: FileSystems.getFileSystem(uri)
            return Files.list(fs.getPath(dir)).use { paths ->
                paths.map { it.fileName.toString().trimEnd('/') }.toList()
            }
        } finally {
            opened?.close()
        }
    }

    /**
     * Every class plugin.xml names exists.
     *
     * The platform resolves these by name at load time and logs a warning for one it cannot find,
     * which is not a failure anybody sees — the extension is simply absent, and whatever it was
     * registering silently does not happen. A highlighting pass that never runs and a listener that
     * never fires look exactly like a feature that does not work.
     *
     * Covers every attribute a registration can name an implementation with, so a new kind of
     * extension is covered the day it is added rather than the day somebody remembers to add it
     * here.
     */
    @Test
    fun `every class named in plugin xml resolves`() {
        val named = listOf("implementation", "implementationClass", "class", "instance", "factoryClass", "serviceImplementation", "interface", "topic")
            .flatMap { attribute ->
                Regex("""$attribute="([^"]+)"""").findAll(pluginXml).map { it.groupValues[1] }
            }
            // an inner class is named with a `$`, which `Class.forName` takes as written
            .filter { it.contains('.') && !it.contains(' ') }
            .distinct()

        assertTrue(named.size > 20, "plugin.xml named only ${named.size} classes, which is too few to be right")

        val missing = named.filter { runCatching { Class.forName(it) }.isFailure }
        assertTrue(
            missing.isEmpty(),
            "plugin.xml names classes that do not exist, so the platform will skip them: $missing",
        )
    }

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
     * `internalFileTemplate` names a file under `fileTemplates/internal` whose name is the
     * declaration plus the produced extension plus `.ft`. Nothing resolves the name until the user
     * picks the kind in New →, and the failure surfaces as "Template not found: <name>" in a
     * balloon rather than at startup — which is how a branding pass that lowercased the
     * declarations without renaming the files went unnoticed.
     */
    @Test
    fun `every declared internal file template resolves`() {
        val declared = attr("internalFileTemplate", "name")
        assertTrue(declared.isNotEmpty(), "expected internal file templates to be declared")
        val present = listResource("/fileTemplates/internal")
        val missing = declared.filter { name ->
            present.none { it.startsWith("$name.") && it.endsWith(".ft") }
        }
        assertTrue(
            missing.isEmpty(),
            "internalFileTemplate names with no fileTemplates/internal/<name>.<ext>.ft file: " +
                "$missing; present: $present. The lookup is case-sensitive inside a jar",
        )
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
     * icon, which is either a plugin resource path (`/icons/x.svg`) or a field read reflectively
     * off a class (`AllIcons.Nodes.Class`). Neither is resolved by the compiler, and both fail at
     * runtime — when the user clicks the stripe button.
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
            if (reference.startsWith("/")) {
                assertNotNull(
                    javaClass.getResource(reference),
                    "toolWindow icon=\"$reference\" resolves to no resource",
                )
                continue
            }
            val field = reference.substringAfterLast('.')
            val owner = "com.intellij.icons." + reference.dropLast(field.length + 1).replace('.', '$')
            assertNotNull(
                runCatching { Class.forName(owner).getField(field).get(null) }.getOrNull(),
                "toolWindow icon=\"$reference\" resolves to no icon field",
            )
        }
    }
}
