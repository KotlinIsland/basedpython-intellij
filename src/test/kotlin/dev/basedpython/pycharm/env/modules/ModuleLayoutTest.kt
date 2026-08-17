package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The questions the structure page asks of a layout, none of which need a disk.
 *
 * The one that matters is [ModuleLayout.dependents]: it decides what a removal has to un-declare
 * first, and an answer that is one module short is a workspace that stops resolving.
 */
class ModuleLayoutTest {

    private fun module(
        name: String,
        path: String = "packages/$name",
        dependencies: List<ModuleDependency> = emptyList(),
        isRoot: Boolean = false,
        packaged: Boolean = true,
    ) = ProjectModule(
        name = name,
        root = Path.of("/project").resolve(path),
        relativePath = path,
        version = "0.1.0",
        description = null,
        requiresPython = ">=3.12",
        dependencies = dependencies,
        packaged = packaged,
        isRoot = isRoot,
        memberEntry = null,
    )

    private val root = module("root", path = "", isRoot = true, packaged = false, dependencies = listOf(
        ModuleDependency("alpha", EnvDependencyTarget.Main),
    ))

    private val alpha = module(
        "alpha",
        dependencies = listOf(
            ModuleDependency("beta", EnvDependencyTarget.Main),
            ModuleDependency("beta", EnvDependencyTarget.Group("dev")),
        ),
    )

    private val beta = module("beta")

    private val layout = ModuleLayout(root = root, members = listOf(alpha, beta))

    @Test
    fun `the root comes first, then the members`() {
        assertEquals(listOf("root", "alpha", "beta"), layout.all.map { it.name })
    }

    @Test
    fun `a module is found however its name is spelled`() {
        assertEquals("alpha", layout.byName("Alpha")?.name)
        assertEquals("alpha", layout.byName("alpha")?.name)
    }

    @Test
    fun `dependents are the modules that declare it, in any list`() {
        assertEquals(listOf("alpha"), layout.dependents("beta").map { it.name })
        assertEquals(listOf("root"), layout.dependents("alpha").map { it.name })
        assertTrue(layout.dependents("root").isEmpty())
    }

    /**
     * Both lists, because removing the module means two commands: `uv remove` without the group flag
     * takes it out of the main list and reports success, leaving `dev` declaring something gone.
     */
    @Test
    fun `a module declared twice reports both lists`() {
        assertEquals(
            listOf(EnvDependencyTarget.Main, EnvDependencyTarget.Group("dev")),
            alpha.dependsOn("beta"),
        )
        assertTrue(beta.dependsOn("alpha").isEmpty())
    }

    @Test
    fun `only a built module that is not the root can be depended on`() {
        assertTrue(beta.isImportable)
        assertFalse(root.isImportable)
        assertFalse(module("script", packaged = false).isImportable)
    }

    @Test
    fun `a name is normalised the way every packaging tool normalises it`() {
        assertEquals("my-lib", ModuleNames.normalize("My_Lib"))
        assertEquals("my-lib", ModuleNames.normalize("my.lib"))
        assertEquals("my-lib", ModuleNames.normalize("my--lib"))
        assertEquals("my_lib", ModuleNames.importName("My.Lib"))
    }

    @Test
    fun `a name has to be one a manifest can carry`() {
        assertTrue(ModuleNames.isValid("alpha"))
        assertTrue(ModuleNames.isValid("my-lib2"))
        assertFalse(ModuleNames.isValid(""))
        assertFalse(ModuleNames.isValid("-lib"))
        assertFalse(ModuleNames.isValid("lib-"))
        assertFalse(ModuleNames.isValid("my lib"))
        assertFalse(ModuleNames.isValid("my/lib"))
    }
}
