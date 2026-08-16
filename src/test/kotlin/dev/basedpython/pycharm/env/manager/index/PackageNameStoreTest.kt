package dev.basedpython.pycharm.env.manager.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The package catalogue on disk, and prefix lookups over it.
 *
 * The binary search is the part worth testing hard: it walks byte offsets over a text file, so every
 * boundary it can land on — the first line, the last line, a prefix that matches nothing, a prefix
 * that sorts before or after everything — is a separate way to get it subtly wrong, and getting it
 * wrong shows up as "completion silently misses some packages" rather than as a crash.
 */
class PackageNameStoreTest {

    private fun store(dir: Path, vararg names: String): PackageNameStore {
        val file = dir.resolve("catalogue.txt")
        PackageNameStore.write(file, names.toList())
        return PackageNameStore(file)
    }

    // ---- normalisation ------------------------------------------------------

    /** PEP 503: this is why typing `flask_sqlalchemy` finds `Flask-SQLAlchemy`. */
    @Test
    fun `names are compared in the index's normalised form`() {
        assertEquals("flask-sqlalchemy", PackageNameStore.normalise("Flask-SQLAlchemy"))
        assertEquals("flask-sqlalchemy", PackageNameStore.normalise("flask_sqlalchemy"))
        assertEquals("flask-sqlalchemy", PackageNameStore.normalise("flask.sqlalchemy"))
        assertEquals("zope-interface", PackageNameStore.normalise("  zope.interface  "))
        // Each *run* of separators collapses to one `-`, so two runs give two dashes. Not the same
        // project as `Flask-SQLAlchemy`, and PEP 503 says so.
        assertEquals("flask-sql-alchemy", PackageNameStore.normalise("Flask__SQL.Alchemy"))
    }

    /** Leading and trailing separators normalise away rather than becoming a leading `-`. */
    @Test
    fun `separators at the edges do not survive`() {
        assertEquals("httpx", PackageNameStore.normalise("-httpx"))
        assertEquals("httpx", PackageNameStore.normalise("httpx-"))
        assertEquals("httpx", PackageNameStore.normalise("._httpx__"))
        assertEquals("", PackageNameStore.normalise("---"))
        assertEquals("", PackageNameStore.normalise(""))
    }

    // ---- lookup -------------------------------------------------------------

    @Test
    fun `a prefix finds every name that starts with it`(@TempDir dir: Path) {
        val store = store(dir, "httpx", "httpcore", "http-parser", "requests", "rich")

        assertEquals(listOf("http-parser", "httpcore", "httpx"), store.startingWith("http"))
        assertEquals(listOf("httpx"), store.startingWith("httpx"))
        assertEquals(listOf("requests", "rich"), store.startingWith("r"))
    }

    /** The user's spelling should not have to match the index's. */
    @Test
    fun `a prefix is matched in normalised form and the original spelling comes back`(@TempDir dir: Path) {
        val store = store(dir, "Flask-SQLAlchemy", "zope.interface")

        assertEquals(listOf("Flask-SQLAlchemy"), store.startingWith("flask_sql"))
        assertEquals(listOf("Flask-SQLAlchemy"), store.startingWith("FLASK-SQL"))
        assertEquals(listOf("zope.interface"), store.startingWith("zope-int"))
    }

    @Test
    fun `a prefix nothing starts with finds nothing`(@TempDir dir: Path) {
        val store = store(dir, "httpx", "requests")

        assertTrue(store.startingWith("zzz").isEmpty(), "sorts after everything")
        assertTrue(store.startingWith("aaa").isEmpty(), "sorts before everything")
        assertTrue(store.startingWith("httpy").isEmpty(), "between two entries")
    }

    /** Every entry has to be reachable, including the ones at the very edges of the file. */
    @Test
    fun `the first and last entries are found`(@TempDir dir: Path) {
        val store = store(dir, "aaa", "bbb", "ccc", "zzz")

        assertEquals(listOf("aaa"), store.startingWith("aaa"))
        assertEquals(listOf("zzz"), store.startingWith("zzz"))
    }

    /**
     * The real shape of the problem: many entries, so the search actually bisects rather than
     * stumbling onto the answer. Every single name must be findable by its own full name.
     */
    @Test
    fun `every entry in a large catalogue is findable`(@TempDir dir: Path) {
        val names = (0 until 5000).map { "pkg-%05d".format(it) }
        val store = store(dir, *names.toTypedArray())

        for (name in names) {
            assertEquals(listOf(name), store.startingWith(name), "could not find $name")
        }
    }

    @Test
    fun `results are capped`(@TempDir dir: Path) {
        val names = (0 until 500).map { "pkg-%03d".format(it) }
        val store = store(dir, *names.toTypedArray())

        assertEquals(PackageNameStore.MAX_RESULTS, store.startingWith("pkg").size)
        assertEquals(3, store.startingWith("pkg", limit = 3).size)
    }

    /** Showing the alphabetical head of an index is showing its numeric junk. */
    @Test
    fun `a blank prefix finds nothing rather than the head of the catalogue`(@TempDir dir: Path) {
        val store = store(dir, "aaa", "bbb")

        assertTrue(store.startingWith("").isEmpty())
        assertTrue(store.startingWith("   ").isEmpty())
        assertTrue(store.startingWith("-").isEmpty(), "normalises to nothing")
    }

    @Test
    fun `containment is exact, not prefix`(@TempDir dir: Path) {
        val store = store(dir, "httpx", "httpcore")

        assertTrue(store.contains("httpx"))
        assertTrue(store.contains("HTTPX"))
        assertFalse(store.contains("http"))
        assertFalse(store.contains("httpxx"))
    }

    // ---- the file itself ----------------------------------------------------

    @Test
    fun `a missing catalogue is empty rather than an error`(@TempDir dir: Path) {
        val store = PackageNameStore(dir.resolve("absent.txt"))

        assertFalse(store.exists)
        assertEquals(null, store.lastModified())
        assertTrue(store.startingWith("http").isEmpty())
        assertFalse(store.contains("httpx"))
    }

    /** Two spellings of one project are one entry. */
    @Test
    fun `duplicates collapse`(@TempDir dir: Path) {
        val store = store(dir, "httpx", "httpx", "HTTPX")

        // `httpx` and `HTTPX` normalise the same but are stored under their own spellings; the exact
        // duplicate is what must not appear twice.
        assertEquals(2, store.startingWith("httpx").size)
        assertEquals(listOf("HTTPX", "httpx"), store.startingWith("httpx").sorted())
    }

    /**
     * A fetch that dies partway must not replace a good catalogue with an empty one, so a writer
     * that was given nothing writes nothing.
     */
    @Test
    fun `writing no names leaves an existing catalogue alone`(@TempDir dir: Path) {
        val file = dir.resolve("catalogue.txt")
        PackageNameStore.write(file, listOf("httpx"))
        PackageNameStore.Writer(file).use { /* nothing added */ }

        assertEquals(listOf("httpx"), PackageNameStore(file).startingWith("httpx"))
    }

    /** A catalogue is only ever swapped in whole — a reader never sees a half-written file. */
    @Test
    fun `the catalogue is moved into place, leaving no partial file behind`(@TempDir dir: Path) {
        val file = dir.resolve("catalogue.txt")
        PackageNameStore.write(file, listOf("httpx", "requests"))

        assertTrue(Files.isRegularFile(file))
        assertFalse(Files.exists(file.resolveSibling("catalogue.txt.part")))
    }
}
