package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading uv's JSON.
 *
 * The fixtures are verbatim output from uv 0.12.3 against a real project — not JSON written to suit
 * the parser. That is the point of them: the fields this reads are another program's, and a fixture
 * invented here would keep passing after uv renamed one.
 */
class UvOutputTest {

    /** `uv pip list --format json` in a project with one dependency and an editable install of itself. */
    private val pipList = """
        [{"name":"certifi","version":"2026.7.22"},
         {"name":"charset-normalizer","version":"3.5.1"},
         {"name":"envdemo","version":"0.1.0","editable_project_location":"/tmp/envdemo"},
         {"name":"idna","version":"3.18"},
         {"name":"requests","version":"2.34.2"},
         {"name":"urllib3","version":"2.7.0"}]
    """.trimIndent()

    /**
     * `uv python list --output-format json`, trimmed to the shapes that matter: an installed
     * interpreter reached through a symlink, the same one under uv's own directory, a stable
     * release, a release candidate, and a download candidate with no path.
     */
    private val pythonList = """
        [{"key":"cpython-3.15.0rc1-macos-aarch64-none","version":"3.15.0rc1",
          "path":"/Users/x/.local/bin/python3.15",
          "symlink":"/Users/x/.local/share/uv/python/cpython-3.15.0rc1-macos-aarch64-none/bin/python3.15",
          "url":null,"os":"macos","implementation":"cpython","arch":"aarch64"},
         {"key":"cpython-3.15.0rc1-macos-aarch64-none","version":"3.15.0rc1",
          "path":"/Users/x/.local/share/uv/python/cpython-3.15-macos-aarch64-none/bin/python3.15",
          "symlink":null,"url":null,"os":"macos","implementation":"cpython","arch":"aarch64"},
         {"key":"cpython-3.12.8-macos-aarch64-none","version":"3.12.8",
          "path":"/Users/x/.local/share/uv/python/cpython-3.12.8-macos-aarch64-none/bin/python3.12",
          "symlink":null,"url":null,"os":"macos","implementation":"cpython","arch":"aarch64"},
         {"key":"cpython-3.13.1-macos-aarch64-none","version":"3.13.1",
          "path":null,"symlink":null,
          "url":"https://example.invalid/cpython-3.13.1.tar.gz",
          "os":"macos","implementation":"cpython","arch":"aarch64"},
         {"key":"pypy-3.11.0-macos-aarch64-none","version":"3.11.0",
          "path":null,"symlink":null,"url":"https://example.invalid/pypy.tar.gz",
          "os":"macos","implementation":"pypy","arch":"aarch64"}]
    """.trimIndent()

    @Test
    fun `packages come back sorted, with the editable install identified`() {
        val packages = UvBackend.parsePackages(pipList)

        assertEquals(
            listOf("certifi", "charset-normalizer", "envdemo", "idna", "requests", "urllib3"),
            packages.map { it.name },
        )
        assertEquals("2.34.2", packages.first { it.name == "requests" }.version)

        val own = packages.first { it.name == "envdemo" }
        assertEquals("/tmp/envdemo", own.editableLocation)
        assertTrue(own.isEditable)
        assertTrue(packages.first { it.name == "requests" }.editableLocation == null)
    }

    /** uv's own order is the resolver's; a list that reorders between refreshes is unreadable. */
    @Test
    fun `sorting ignores case`() {
        val packages = UvBackend.parsePackages("""[{"name":"Zope","version":"1"},{"name":"attrs","version":"2"}]""")
        assertEquals(listOf("attrs", "Zope"), packages.map { it.name })
    }

    @Test
    fun `an interpreter listed twice is reported once, as installed`() {
        val pythons = UvBackend.parsePythons(pythonList)

        val rc = pythons.filter { it.version == "3.15.0rc1" }
        assertEquals(1, rc.size, "the symlink and its target are one interpreter")
        assertTrue(rc.single().isInstalled)
    }

    @Test
    fun `a download candidate has no path and an installed one does`() {
        val pythons = UvBackend.parsePythons(pythonList).associateBy { it.version }

        assertTrue(pythons.getValue("3.12.8").isInstalled)
        assertEquals(false, pythons.getValue("3.13.1").isInstalled)
        assertNull(pythons.getValue("3.13.1").path)
        assertEquals("pypy", pythons.getValue("3.11.0").implementation)
    }

    @Test
    fun `installed interpreters sort ahead of downloadable ones`() {
        val pythons = UvBackend.parsePythons(pythonList)
        val firstDownload = pythons.indexOfFirst { !it.isInstalled }
        val lastInstalled = pythons.indexOfLast { it.isInstalled }
        assertTrue(lastInstalled < firstDownload, "installed first: ${pythons.map { it.key to it.isInstalled }}")
    }

    @Test
    fun `the feature version is what a version request is written as`() {
        val candidate = UvBackend.parsePythons(pythonList).first { it.version == "3.12.8" }
        assertEquals("3.12", candidate.featureVersion)
    }

    /**
     * This parses another program's output on every refresh. A uv that failed, printed a warning
     * before its array, or was killed mid-write must produce a short list, not an exception that
     * takes the scan down.
     */
    @Test
    fun `malformed output degrades to nothing rather than throwing`() {
        assertEquals(emptyList<EnvPackage>(), UvBackend.parsePackages(""))
        assertEquals(emptyList<EnvPackage>(), UvBackend.parsePackages("error: no interpreter found"))
        assertEquals(emptyList<EnvPackage>(), UvBackend.parsePackages("""[{"name":"a","version":"1"}"""))
        assertEquals(emptyList<EnvPackage>(), UvBackend.parsePackages("""{"not":"an array"}"""))
        assertEquals(emptyList<PythonCandidate>(), UvBackend.parsePythons(""))
    }

    /** An entry missing the one field that identifies it is dropped; its neighbours are not. */
    @Test
    fun `an entry without a name is skipped and the rest survive`() {
        val packages = UvBackend.parsePackages(
            """[{"version":"1"},{"name":"attrs","version":"2"},"a string",null]""",
        )
        assertEquals(listOf("attrs"), packages.map { it.name })
    }

    @Test
    fun `a package with no version is kept, since it is still installed`() {
        val packages = UvBackend.parsePackages("""[{"name":"attrs"}]""")
        assertEquals(listOf("attrs" to ""), packages.map { it.name to it.version })
    }
}
