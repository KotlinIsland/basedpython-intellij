package dev.basedpython.pycharm.env.manager.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Reading a PEP 691 index.
 *
 * The offline half runs on fixtures that are verbatim excerpts of PyPI's own documents — the
 * `provides_extra` list below is httpx's real one — because these are another service's shapes and
 * an invented fixture would keep passing after PyPI renamed a field.
 *
 * The live half is gated on `BASEDPYTHON_ALLOW_NETWORK_TESTS=1`: it downloads 9.5 MB.
 */
class PyPiIndexTest {

    private companion object {
        const val NETWORK = "BASEDPYTHON_ALLOW_NETWORK_TESTS"
    }

    // ---- the catalogue document --------------------------------------------

    @Test
    fun `names are pulled out of a PEP 691 document`() {
        val document = """
            {"meta":{"_last-serial":40068976,"api-version":"1.4"},
             "projects":[{"_last-serial":3075854,"name":"0"},
                         {"_last-serial":1,"name":"httpx"},
                         {"name":"Flask-SQLAlchemy"}]}
        """.trimIndent()

        val names = mutableListOf<String>()
        PyPiIndex.readNames(document) { names += it }

        assertEquals(listOf("0", "httpx", "Flask-SQLAlchemy"), names)
    }

    /** `meta` comes first in the real document and carries fields this does not read. */
    @Test
    fun `unknown fields are skipped rather than rejected`() {
        val document = """
            {"meta":{"api-version":"1.4","something-new":{"nested":[1,2,3]}},
             "tracks":["ignored"],
             "projects":[{"name":"httpx","yanked":false}]}
        """.trimIndent()

        val names = mutableListOf<String>()
        PyPiIndex.readNames(document) { names += it }

        assertEquals(listOf("httpx"), names)
    }

    @Test
    fun `a document with no projects yields nothing`() {
        val names = mutableListOf<String>()
        PyPiIndex.readNames("""{"meta":{"api-version":"1.4"},"projects":[]}""") { names += it }
        assertTrue(names.isEmpty())
    }

    // ---- the package document ----------------------------------------------

    /** Verbatim fields from `https://pypi.org/pypi/httpx/json`, trimmed to what is read. */
    private val httpxDocument = """
        {"info":{"name":"httpx","version":"0.28.1",
                 "summary":"The next generation HTTP client.",
                 "home_page":null,
                 "package_url":"https://pypi.org/project/httpx/",
                 "provides_extra":["brotli","cli","http2","socks","zstd"]},
         "releases":{},"urls":[]}
    """.trimIndent()

    @Test
    fun `a package's extras, version and summary come through`() {
        val details = requireNotNull(PyPiIndex.parseDetails("httpx", httpxDocument))

        assertEquals("httpx", details.name)
        assertEquals("0.28.1", details.latestVersion)
        assertEquals("The next generation HTTP client.", details.summary)
        assertEquals(listOf("brotli", "cli", "http2", "socks", "zstd"), details.extras)
        assertEquals("https://pypi.org/project/httpx/", details.homepage)
    }

    /** Most packages declare none, and older metadata omits the field entirely. */
    @Test
    fun `a package with no extras reports none rather than failing`() {
        val details = requireNotNull(
            PyPiIndex.parseDetails("six", """{"info":{"name":"six","version":"1.16.0"}}"""),
        )
        assertTrue(details.extras.isEmpty())
        assertNull(details.summary)

        val explicitNull = requireNotNull(
            PyPiIndex.parseDetails("six", """{"info":{"name":"six","provides_extra":null}}"""),
        )
        assertTrue(explicitNull.extras.isEmpty())
    }

    @Test
    fun `extras are de-duplicated and sorted, so the checkbox order is stable`() {
        val details = requireNotNull(
            PyPiIndex.parseDetails("x", """{"info":{"name":"x","provides_extra":["cli","brotli","cli"]}}"""),
        )
        assertEquals(listOf("brotli", "cli"), details.extras)
    }

    @Test
    fun `a document that is not a package document yields nothing`() {
        assertNull(PyPiIndex.parseDetails("x", ""))
        assertNull(PyPiIndex.parseDetails("x", "Not Found"))
        assertNull(PyPiIndex.parseDetails("x", """{"message":"Not Found"}"""))
        assertNull(PyPiIndex.parseDetails("x", """{"info":"""))
    }

    // ---- cache identity -----------------------------------------------------

    /**
     * Serving a private mirror's catalogue to a project pointed at the public index would be worse
     * than having no cache at all, so the id has to separate them.
     */
    @Test
    fun `different indexes get different cache identities`() {
        val pypi = PyPiIndex.idFor("https://pypi.org/simple")
        val mirror = PyPiIndex.idFor("https://packages.internal.example/simple")

        assertTrue(pypi != mirror)
        assertEquals(pypi, PyPiIndex.idFor("https://pypi.org/simple"), "stable across calls")
        assertTrue(pypi.startsWith("pypi.org"), pypi)
        // Two paths on one host are different catalogues.
        assertTrue(PyPiIndex.idFor("https://h/a") != PyPiIndex.idFor("https://h/b"))
    }

    @Test
    fun `a cache identity is safe to use as a directory name`() {
        for (url in listOf("https://pypi.org/simple", "http://user:pw@h:8080/x/y", "not a url")) {
            val id = PyPiIndex.idFor(url)
            assertTrue(id.none { it in "/\\:?*\"<>|" }, "unsafe id for $url: $id")
            assertTrue(id.isNotEmpty())
        }
    }

    // ---- live ---------------------------------------------------------------

    /**
     * The whole catalogue, streamed from the real PyPI into a real store, then searched.
     *
     * This is the test that says the streaming reader survives a 42 MB document and that the result
     * is actually usable — the offline tests prove the parse on three entries, which is not the same
     * claim.
     */
    @Test
    fun `the real catalogue downloads, stores and searches`(@TempDir dir: Path) {
        assumeTrue(System.getenv(NETWORK) == "1", "set $NETWORK=1 to allow the download")

        val index = PyPiIndex.pypi()
        val file = dir.resolve("catalogue.txt")
        var count = 0
        PackageNameStore.Writer(file).use { writer ->
            index.fetchNames { writer.add(it); count++ }
        }

        assertTrue(count > 500_000, "expected a full catalogue, got $count names")
        val store = PackageNameStore(file)
        assertTrue(store.contains("httpx"), "httpx is in the catalogue")
        assertTrue(store.contains("Flask-SQLAlchemy"), "found under its own spelling")
        assertTrue(store.contains("flask_sqlalchemy"), "and under a normalised one")
        assertTrue(store.startingWith("httpx").isNotEmpty())
        assertTrue(store.startingWith("requests").contains("requests"))
    }

    @Test
    fun `the real package endpoint reports httpx's extras`() {
        assumeTrue(System.getenv(NETWORK) == "1", "set $NETWORK=1 to allow the request")

        val details = requireNotNull(PyPiIndex.pypi().fetchDetails("httpx"))
        assertEquals("httpx", details.name.lowercase())
        assertNotNull(details.latestVersion)
        assertTrue(details.extras.contains("http2"), "extras were ${details.extras}")
    }

    @Test
    fun `a package the index has never heard of reports nothing`() {
        assumeTrue(System.getenv(NETWORK) == "1", "set $NETWORK=1 to allow the request")
        assertNull(PyPiIndex.pypi().fetchDetails("this-package-does-not-exist-basedpython-test"))
    }
}
