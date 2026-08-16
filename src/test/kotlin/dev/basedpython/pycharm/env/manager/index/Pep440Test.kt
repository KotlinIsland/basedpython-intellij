package dev.basedpython.pycharm.env.manager.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Version ordering and `requires_python` matching.
 *
 * The specifiers below are real ones lifted from PyPI — urllib3's compound
 * `>=2.7, !=3.0.*, …, <4` in particular — because these decide whether a release is offered to the
 * user as installable, and getting one wrong either hides a good version or offers a broken one.
 */
class Pep440Test {

    private fun sorted(vararg versions: String) = versions.sortedWith { a, b -> Pep440.compare(a, b) }

    // ---- ordering -----------------------------------------------------------

    /** The reason string sorting will not do. */
    @Test
    fun `release segments compare numerically, not as text`() {
        assertEquals(listOf("1.9", "1.10", "1.11"), sorted("1.11", "1.9", "1.10"))
        assertEquals(listOf("2.9.1", "2.10.0"), sorted("2.10.0", "2.9.1"))
        assertTrue(Pep440.compare("1.10", "1.9") > 0)
    }

    @Test
    fun `missing components count as zero`() {
        assertEquals(0, Pep440.compare("1.2", "1.2.0"))
        assertEquals(0, Pep440.compare("1.2.0.0", "1.2"))
        assertTrue(Pep440.compare("1.2.1", "1.2") > 0)
    }

    /** A pre-release comes before the release it leads to. */
    @Test
    fun `pre-releases sort below the final release`() {
        assertEquals(
            listOf("2.0.0a1", "2.0.0b1", "2.0.0rc1", "2.0.0"),
            sorted("2.0.0", "2.0.0rc1", "2.0.0a1", "2.0.0b1"),
        )
    }

    @Test
    fun `alpha, beta and rc are recognised however they are spelled`() {
        assertEquals(0, Pep440.compare("1.0alpha1", "1.0a1"))
        assertEquals(0, Pep440.compare("1.0beta2", "1.0b2"))
        assertEquals(0, Pep440.compare("1.0c1", "1.0rc1"))
        assertEquals(0, Pep440.compare("1.0.rc1", "1.0rc1"))
    }

    @Test
    fun `dev sorts below everything and post above`() {
        assertEquals(
            listOf("1.0.dev1", "1.0a1", "1.0", "1.0.post1"),
            sorted("1.0.post1", "1.0", "1.0a1", "1.0.dev1"),
        )
    }

    @Test
    fun `an epoch outranks the release segment`() {
        assertTrue(Pep440.compare("1!1.0", "2.0") > 0)
        assertEquals(listOf("2.0", "1!1.0"), sorted("1!1.0", "2.0"))
    }

    @Test
    fun `a leading v and surrounding space are tolerated`() {
        assertEquals(0, Pep440.compare("v1.2.3", "1.2.3"))
        assertEquals(0, Pep440.compare("  1.2.3 ", "1.2.3"))
    }

    /** The index carries some genuinely strange historical versions; one must not sink the list. */
    @Test
    fun `an unparseable version sorts below every real one instead of throwing`() {
        assertFalse(Pep440.isValid("not-a-version"))
        assertTrue(Pep440.compare("not-a-version", "1.0") < 0)
        assertTrue(Pep440.compare("1.0", "not-a-version") > 0)
        assertEquals(listOf("not-a-version", "1.0"), sorted("1.0", "not-a-version"))
    }

    @Test
    fun `newest first is the order a picker offers`() {
        assertEquals(
            listOf("2.1.0", "2.0.0", "2.0.0rc1", "1.26.20"),
            listOf("1.26.20", "2.0.0", "2.1.0", "2.0.0rc1").sortedWith(Pep440.NEWEST_FIRST),
        )
    }

    @Test
    fun `a pre-release knows it is one`() {
        assertTrue(Pep440.isPreRelease("2.0.0a1"))
        assertTrue(Pep440.isPreRelease("2.0.0rc1"))
        assertTrue(Pep440.isPreRelease("1.0.dev1"))
        assertFalse(Pep440.isPreRelease("2.0.0"))
        assertFalse(Pep440.isPreRelease("2.0.0.post1"))
    }

    // ---- specifiers ---------------------------------------------------------

    @Test
    fun `the ordinary comparisons work`() {
        assertTrue(Pep440.satisfies("3.12", ">=3.8"))
        assertTrue(Pep440.satisfies("3.8", ">=3.8"))
        assertFalse(Pep440.satisfies("3.7", ">=3.8"))
        assertTrue(Pep440.satisfies("3.7", "<3.8"))
        assertFalse(Pep440.satisfies("3.8", "<3.8"))
        assertTrue(Pep440.satisfies("3.8", "<=3.8"))
        assertTrue(Pep440.satisfies("3.9", ">3.8"))
        assertTrue(Pep440.satisfies("3.8", "==3.8"))
        assertTrue(Pep440.satisfies("3.8.1", "!=3.9"))
    }

    /** `3.12.7` has to satisfy `>=3.12`, which is how every real interpreter version arrives. */
    @Test
    fun `a patch version satisfies a feature-version bound`() {
        assertTrue(Pep440.satisfies("3.12.7", ">=3.12"))
        assertTrue(Pep440.satisfies("3.13.15", ">=3.8"))
        assertFalse(Pep440.satisfies("3.11.9", ">=3.12"))
    }

    @Test
    fun `wildcards match on the release prefix`() {
        assertTrue(Pep440.satisfies("3.0.1", "==3.0.*"))
        assertFalse(Pep440.satisfies("3.1.0", "==3.0.*"))
        assertFalse(Pep440.satisfies("3.0.1", "!=3.0.*"))
        assertTrue(Pep440.satisfies("3.1.0", "!=3.0.*"))
    }

    /** urllib3's own, verbatim — the shape that string handling cannot answer. */
    @Test
    fun `a real compound specifier is evaluated clause by clause`() {
        val spec = ">=2.7, !=3.0.*, !=3.1.*, !=3.2.*, !=3.3.*, !=3.4.*, !=3.5.*, <4"

        assertTrue(Pep440.satisfies("3.12.7", spec))
        assertTrue(Pep440.satisfies("2.7.18", spec))
        assertFalse(Pep440.satisfies("3.4.10", spec), "excluded by !=3.4.*")
        assertFalse(Pep440.satisfies("2.6", spec), "below >=2.7")
        assertFalse(Pep440.satisfies("4.0", spec), "at or above <4")
    }

    /** The same specifier without spaces, which is equally common in the wild. */
    @Test
    fun `clauses without spaces parse the same`() {
        val spec = "!=3.0.*,!=3.1.*,!=3.2.*,!=3.3.*,!=3.4.*,!=3.5.*,>=2.7"
        assertTrue(Pep440.satisfies("3.12", spec))
        assertFalse(Pep440.satisfies("3.5.9", spec))
    }

    @Test
    fun `compatible release covers the last component only`() {
        assertTrue(Pep440.satisfies("2.2.5", "~=2.2"))
        assertTrue(Pep440.satisfies("2.9", "~=2.2"))
        assertFalse(Pep440.satisfies("3.0", "~=2.2"))
        assertFalse(Pep440.satisfies("2.1", "~=2.2"))
    }

    @Test
    fun `an arbitrary equality is a string match`() {
        assertTrue(Pep440.satisfies("1.0+local", "===1.0+local"))
        assertFalse(Pep440.satisfies("1.0", "===1.0+local"))
    }

    /**
     * A package that declares no `requires_python` is not claiming incompatibility — it is saying
     * nothing — so it must not be marked as unusable.
     */
    @Test
    fun `no specifier is satisfied by anything`() {
        assertTrue(Pep440.satisfies("3.12", null))
        assertTrue(Pep440.satisfies("3.12", ""))
        assertTrue(Pep440.satisfies("3.12", "   "))
    }

    /**
     * Marking a release incompatible because of a gap in *this* parser would hide a perfectly good
     * version behind our own limitation, so an unreadable clause is treated as satisfied.
     */
    @Test
    fun `a clause this cannot read is not treated as a failure`() {
        assertTrue(Pep440.satisfies("3.12", "something weird"))
        assertTrue(Pep440.satisfies("3.12", ">=3.8, and then some nonsense"))
    }
}
