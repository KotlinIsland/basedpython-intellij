package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.manager.index.PackageNameStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The search behind the Add Package results list.
 *
 * A plain filter over the catalogue, which is the point: the completion machinery it replaced had
 * to be argued with over autopopup, matching, result caps and restarts, and every fix uncovered the
 * next one. Filtering a list is something that can simply be checked.
 */
class EnvPackageSearchTest {

    private fun store(dir: Path, vararg names: String): PackageNameStore {
        val file = dir.resolve("catalogue.txt")
        PackageNameStore.write(file, names.toList())
        return PackageNameStore(file)
    }

    private val catalogue = arrayOf(
        "b-aws-dynamodb-backup", "b-baka", "ba", "ba-abydos", "bas", "base", "based-cli",
        "basedpython", "bash", "requests",
    )

    /** The symptom that started all this: results must start with the query. */
    @Test
    fun `results start with what was typed`(@TempDir dir: Path) {
        val store = store(dir, *catalogue)

        assertEquals(
            listOf("ba", "ba-abydos", "bas", "base", "based-cli", "basedpython", "bash"),
            EnvPackageSearch.resultsFor(store, "ba"),
        )
        assertTrue(EnvPackageSearch.resultsFor(store, "ba").none { it.startsWith("b-") })
    }

    /** Each keystroke narrows honestly, because each is its own query rather than a filter. */
    @Test
    fun `a longer query narrows to its own matches`(@TempDir dir: Path) {
        val store = store(dir, *catalogue)

        assertEquals(listOf("based-cli", "basedpython"), EnvPackageSearch.resultsFor(store, "based"))
        assertEquals(listOf("basedpython"), EnvPackageSearch.resultsFor(store, "basedp"))
    }

    /**
     * Showing the alphabetical head of a package index means showing its numeric junk, which is a
     * worse first impression than an empty list under a hint.
     */
    @Test
    fun `an empty field searches for nothing`(@TempDir dir: Path) {
        val store = store(dir, *catalogue)

        assertNull(EnvPackageSearch.queryIn(""))
        assertNull(EnvPackageSearch.queryIn("   "))
        assertTrue(EnvPackageSearch.resultsFor(store, "").isEmpty())
    }

    /** Once a specifier, an extra or a URL is typed, the catalogue has nothing left to offer. */
    @Test
    fun `a finished requirement is not searched for`(@TempDir dir: Path) {
        assertNull(EnvPackageSearch.queryIn("httpx>=0.27"))
        assertNull(EnvPackageSearch.queryIn("httpx[http2]"))
        assertNull(EnvPackageSearch.queryIn("git+https://github.com/x/y@main"))
        assertNull(EnvPackageSearch.queryIn("./vendor/lib"))
    }

    /** Only the requirement being typed is searched for; earlier ones are already settled. */
    @Test
    fun `the last requirement on the line is the one searched for`(@TempDir dir: Path) {
        val store = store(dir, *catalogue)

        assertEquals("bas", EnvPackageSearch.queryIn("requests bas"))
        assertEquals(listOf("bas", "base", "based-cli", "basedpython", "bash"),
            EnvPackageSearch.resultsFor(store, "requests bas"))
    }

    /** Picking from the list must not discard what was typed before it. */
    @Test
    fun `choosing a name replaces only the requirement being typed`() {
        assertEquals("basedpython", EnvPackageSearch.replaceLastRequirement("bas", "basedpython"))
        assertEquals(
            "httpx rich basedpython",
            EnvPackageSearch.replaceLastRequirement("httpx rich bas", "basedpython"),
        )
        assertEquals("basedpython", EnvPackageSearch.replaceLastRequirement("", "basedpython"))
    }

    @Test
    fun `the list is capped`(@TempDir dir: Path) {
        val many = (0 until EnvPackageSearch.MAX_RESULTS * 2).map { "pkg-%04d".format(it) }
        val store = store(dir, *many.toTypedArray())

        assertEquals(EnvPackageSearch.MAX_RESULTS, EnvPackageSearch.resultsFor(store, "pkg").size)
    }

    /** With no catalogue yet, the field still works and the list is simply empty. */
    @Test
    fun `a missing catalogue yields no results rather than an error`(@TempDir dir: Path) {
        val store = PackageNameStore(dir.resolve("absent.txt"))
        assertTrue(EnvPackageSearch.resultsFor(store, "ba").isEmpty())
    }
}
