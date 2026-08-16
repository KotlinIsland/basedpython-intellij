package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.manager.index.PackageRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the version picker offers, and what it says about each row.
 *
 * The releases below mirror urllib3's real ones — 1.25 and 2.0.0 are genuinely yanked on PyPI, with
 * "Broken release" as the reason — because the decisions here change what a user installs.
 */
class EnvVersionChoicesTest {

    private fun release(
        version: String,
        yanked: Boolean = false,
        reason: String? = null,
        requiresPython: String? = null,
    ) = PackageRelease(version, yanked, reason, requiresPython)

    private val releases = listOf(
        release("1.26.20", requiresPython = ">=2.7"),
        release("2.0.0", yanked = true, reason = "Broken release", requiresPython = ">=3.7"),
        release("2.1.0", requiresPython = ">=3.8"),
        release("3.0.0", requiresPython = ">=3.14"),
    )

    @Test
    fun `the first row pins nothing, and the rest are newest first`() {
        val choices = EnvVersionChoices.of(releases, "3.12.7")

        assertEquals(null, choices.first().version)
        assertEquals(
            listOf("3.0.0", "2.1.0", "2.0.0", "1.26.20"),
            choices.drop(1).map { it.version },
        )
    }

    /** A resolver will not pick a yanked release, but an explicit pin still gets it. */
    @Test
    fun `a yanked release is offered, marked, and carries its reason`() {
        val yanked = EnvVersionChoices.of(releases, "3.12.7").first { it.version == "2.0.0" }

        assertTrue(yanked.yanked)
        assertTrue(yanked.isQuestionable)
        assertTrue(yanked.label.contains("2.0.0"))
        assertTrue(yanked.label.contains("Broken release"), yanked.label)
    }

    /** "3.0.0 is missing" is a puzzle; "3.0.0 needs Python >=3.14" is an answer. */
    @Test
    fun `a release this environment cannot run is offered, marked with what it needs`() {
        val choices = EnvVersionChoices.of(releases, "3.12.7")
        val tooNew = choices.first { it.version == "3.0.0" }

        assertTrue(tooNew.incompatible)
        assertTrue(tooNew.label.contains(">=3.14"), tooNew.label)
        assertFalse(choices.first { it.version == "2.1.0" }.incompatible)
    }

    /** Both notes can apply, and fixing one only to hit the other is a bad way to find out. */
    @Test
    fun `a release that is both yanked and incompatible says both`() {
        val choices = EnvVersionChoices.of(
            listOf(release("9.9", yanked = true, reason = "bad", requiresPython = ">=3.99")),
            "3.12",
        )
        val row = choices.last()

        assertTrue(row.yanked)
        assertTrue(row.incompatible)
        assertTrue(row.label.contains("bad"), row.label)
        assertTrue(row.label.contains(">=3.99"), row.label)
    }

    /**
     * An environment that does not exist yet cannot rule anything out, and greying out versions on a
     * guess would hide perfectly installable ones.
     */
    @Test
    fun `with no interpreter known, nothing is called incompatible`() {
        val choices = EnvVersionChoices.of(releases, null)
        assertTrue(choices.none { it.incompatible })
    }

    @Test
    fun `a release declaring no requires-python is compatible with everything`() {
        val choices = EnvVersionChoices.of(listOf(release("1.0")), "2.7")
        assertFalse(choices.last().incompatible)
    }

    @Test
    fun `nothing is filtered out`() {
        val choices = EnvVersionChoices.of(releases, "3.12.7")
        assertEquals(releases.size + 1, choices.size)
    }

    @Test
    fun `a version already pinned is the selected row, and an unknown one falls back`() {
        val choices = EnvVersionChoices.of(releases, "3.12.7")

        assertEquals("2.1.0", EnvVersionChoices.select(choices, "2.1.0").version)
        assertEquals(null, EnvVersionChoices.select(choices, "9.9.9").version)
        assertEquals(null, EnvVersionChoices.select(choices, null).version)
    }

    @Test
    fun `a package with no releases offers only the row that pins nothing`() {
        val choices = EnvVersionChoices.of(emptyList(), "3.12")
        assertEquals(1, choices.size)
        assertEquals(null, choices.single().version)
    }
}
