package dev.basedpython.pycharm.env.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * What a rename decides to move, before anything is moved.
 *
 * The rules being checked are the ones that stop a rename doing more than it was asked to: a
 * directory that was never named after the module keeps its name, and a project that spells its
 * directories the import way goes on spelling them that way. Each of them is a way to quietly
 * restructure somebody's project while claiming to rename one thing.
 */
class ModuleRenamePlanTest {

    private val projectRoot: Path = Path.of("/project")

    private fun module(
        name: String,
        directory: String = "packages/$name",
        memberEntry: String? = null,
    ) = ProjectModule(
        name = name,
        root = projectRoot.resolve(directory),
        relativePath = directory,
        version = "0.1.0",
        description = null,
        requiresPython = null,
        dependencies = emptyList(),
        packaged = true,
        isRoot = false,
        memberEntry = memberEntry,
    )

    /** Nothing on disk, for the cases where only the naming rules are under test. */
    private val nothingExists: (Path) -> Boolean = { false }

    private fun exists(vararg paths: String): (Path) -> Boolean = { path ->
        paths.any { path == projectRoot.resolve(it) }
    }

    @Test
    fun `a src layout moves the import package and then the directory`() {
        val plan = ModuleRenamePlan.of(
            module("alpha"),
            "beta",
            exists("packages/alpha/src/alpha"),
        )!!

        assertEquals(
            listOf(
                ModuleRenamePlan.Move(
                    projectRoot.resolve("packages/alpha/src/alpha"),
                    projectRoot.resolve("packages/alpha/src/beta"),
                ),
                ModuleRenamePlan.Move(
                    projectRoot.resolve("packages/alpha"),
                    projectRoot.resolve("packages/beta"),
                ),
            ),
            plan.moves(),
            "the package inside has to move while the directory is still where it was",
        )
    }

    @Test
    fun `a flat layout is recognised too`() {
        val plan = ModuleRenamePlan.of(module("alpha"), "beta", exists("packages/alpha/alpha"))!!
        assertEquals(
            projectRoot.resolve("packages/alpha/alpha"),
            plan.importPackage?.from,
        )
        assertEquals(projectRoot.resolve("packages/alpha/beta"), plan.importPackage?.to)
    }

    @Test
    fun `a module with no importable package moves only its directory`() {
        val plan = ModuleRenamePlan.of(module("alpha"), "beta", nothingExists)!!
        assertNull(plan.importPackage)
        assertEquals(
            listOf(
                ModuleRenamePlan.Move(
                    projectRoot.resolve("packages/alpha"),
                    projectRoot.resolve("packages/beta"),
                ),
            ),
            plan.moves(),
        )
    }

    /** A directory called something else was called that on purpose. */
    @Test
    fun `a directory not named after the module keeps its name`() {
        val plan = ModuleRenamePlan.of(
            module("alpha", directory = "packages/legacy-thing", memberEntry = "packages/legacy-thing"),
            "beta",
            exists("packages/legacy-thing/src/alpha"),
        )!!

        assertNull(plan.moduleDirectory)
        assertNull(plan.memberEntry, "the entry names a directory that is not moving")
        assertEquals(
            projectRoot.resolve("packages/legacy-thing/src/beta"),
            plan.importPackage?.to,
            "the import package is still named after the module, so it still follows it",
        )
    }

    /**
     * `my-lib` is imported as `my_lib`, and the two spellings are not interchangeable: one is what a
     * dependent writes in its manifest and the other is what an `import` statement says.
     */
    @Test
    fun `the import package follows the import spelling`() {
        val plan = ModuleRenamePlan.of(
            module("my-lib", directory = "packages/my-lib"),
            "my-thing",
            exists("packages/my-lib/src/my_lib"),
        )!!

        assertEquals(projectRoot.resolve("packages/my-lib/src/my_thing"), plan.importPackage?.to)
        assertEquals(projectRoot.resolve("packages/my-thing"), plan.moduleDirectory?.to)
    }

    /** A project whose directories are spelled the import way keeps being spelled that way. */
    @Test
    fun `a directory spelled with underscores stays spelled with underscores`() {
        val plan = ModuleRenamePlan.of(
            module("my-lib", directory = "packages/my_lib"),
            "my-thing",
            nothingExists,
        )!!

        assertEquals(projectRoot.resolve("packages/my_thing"), plan.moduleDirectory?.to)
    }

    @Test
    fun `the members entry follows the directory it names`() {
        val plan = ModuleRenamePlan.of(
            module("alpha", memberEntry = "packages/alpha"),
            "beta",
            nothingExists,
        )!!

        assertEquals(ModuleRenamePlan.Move.Text("packages/alpha", "packages/beta"), plan.memberEntry)
    }

    @Test
    fun `a module covered by a glob has no entry to follow`() {
        val plan = ModuleRenamePlan.of(module("alpha", memberEntry = null), "beta", nothingExists)!!
        assertNull(plan.memberEntry)
    }

    @Test
    fun `the distribution name is the one thing that always changes`() {
        val plan = ModuleRenamePlan.of(module("alpha"), "beta", nothingExists)!!
        assertEquals(ModuleRenamePlan.Move.Text("alpha", "beta"), plan.distribution)
    }

    /** `my-lib` and `my_lib` are the same distribution, so this is not a rename at all. */
    @Test
    fun `a name that normalises to the same thing is not a rename`() {
        assertNull(ModuleRenamePlan.of(module("my-lib"), "my_lib", nothingExists))
        assertNull(ModuleRenamePlan.of(module("alpha"), "alpha", nothingExists))
    }

    @Test
    fun `a name a manifest could not carry is refused`() {
        assertNull(ModuleRenamePlan.of(module("alpha"), "", nothingExists))
        assertNull(ModuleRenamePlan.of(module("alpha"), "-beta", nothingExists))
        assertNull(ModuleRenamePlan.of(module("alpha"), "be ta", nothingExists))
    }
}
