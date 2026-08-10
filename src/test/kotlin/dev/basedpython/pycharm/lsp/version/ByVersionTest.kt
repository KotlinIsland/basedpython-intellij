package dev.basedpython.pycharm.lsp.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parsing `by --version`, and the floor it is compared against.
 *
 * The floor is the part that bites: it is a number in this repo compared against a number from
 * another project, with nothing linking them. Setting it above a version that exists turns the
 * check into a permanent false alarm for everybody.
 */
class ByVersionTest {

    /** The versions basedpython has actually shipped, newest last. */
    private val realReleases = listOf("0.0.1a3", "0.0.1a9")

    /**
     * The regression that motivated this file: the floor was `0.1.0` while basedpython was at
     * `0.0.1a9`, so every correctly-installed user would have been told to upgrade — and no
     * upgrade existed. It only stayed quiet because the binary answers `by unknown`.
     */
    @Test
    fun `the minimum is not above a version that exists`() {
        val minimum = checkNotNull(ByVersion.parse(MIN_BY_VERSION)) { "MIN_BY_VERSION must parse" }
        for (release in realReleases) {
            val parsed = checkNotNull(ByVersion.parse(release))
            assertTrue(
                parsed >= minimum,
                "MIN_BY_VERSION=$MIN_BY_VERSION would report the real release $release as outdated",
            )
        }
    }

    /** `by --version` on a source build prints this, and it must not parse as a version. */
    @Test
    fun `an unstamped build reports no version rather than a wrong one`() {
        assertNull(ByVersion.parse("by unknown"))
        assertNull(ByVersion.parse(""))
        assertNull(ByVersion.parse(null))
    }

    @Test
    fun `a pre-release suffix parses to its release number`() {
        assertEquals(ByVersion(0, 0, 1), ByVersion.parse("by 0.0.1a9"))
        assertEquals(ByVersion(1, 2, 3), ByVersion.parse("by 1.2.3-rc1"))
    }

    @Test
    fun `missing components default to zero`() {
        assertEquals(ByVersion(0, 4, 0), ByVersion.parse("by version 0.4"))
        assertEquals(ByVersion(2, 0, 0), ByVersion.parse("2"))
    }

    @Test
    fun `versions order by component, not lexically`() {
        assertTrue(ByVersion.parse("0.10.0")!! > ByVersion.parse("0.9.0")!!)
        assertTrue(ByVersion.parse("1.0.0")!! > ByVersion.parse("0.99.99")!!)
        assertEquals(ByVersion.parse("0.0.1"), ByVersion.parse("0.0.1a9"))
    }
}
