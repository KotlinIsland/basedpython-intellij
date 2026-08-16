package dev.basedpython.pycharm.env.manager

import com.intellij.codeInsight.completion.PlainPrefixMatcher
import dev.basedpython.pycharm.env.manager.index.PackageNameStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * How typed text is matched against package names, and why the platform's default is wrong here.
 *
 * Two separate defects produced one symptom — typing `ba` offering `b-aws-dynamodb-backup` — and
 * both are pinned below, because both are invisible until someone types a second character:
 *
 *  1. the matchers on offer both accept far more than a prefix — the platform's camel-hump default
 *     treats the query as a *subsequence*, and `PlainPrefixMatcher`'s one-argument form is
 *     `containsIgnoreCase` — so `ba` "matches" `b-aws-dynamodb-backup` two different ways;
 *  2. the catalogue answer is capped, so the result for `b` is not a superset of the result for
 *     `ba`, and narrowing the first client-side can never produce the second.
 */
class PackageCompletionMatchingTest {

    /** Real names, in the relationship the screenshot showed. */
    private val bDashNames = listOf("b-aws-dynamodb-backup", "b-aws-s3-backup", "b-baka")
    private val baNames = listOf("ba", "ba-abydos", "ba-agent-mcp", "ba-colander")

    /**
     * The trap in the obvious replacement: `PlainPrefixMatcher` is not, by default, a prefix matcher.
     *
     * Its one-argument constructor sets `prefixMatchesOnly = false`, and `prefixMatches` then calls
     * `containsIgnoreCase` — which matches `b-aws-dynamodb-backup` against `ba` all over again, on
     * the `ba` in `backup`. Switching away from camel-hump alone would have changed the wrong names
     * on screen without removing them.
     */
    @Test
    fun `the one-argument plain matcher is contains, not prefix`() {
        val loose = PlainPrefixMatcher("ba")
        assertTrue(
            loose.prefixMatches("b-aws-dynamodb-backup"),
            "still matched, on the 'ba' inside 'backup'",
        )
    }

    /** What this dialog installs: the two-argument form, which is a genuine start match. */
    @Test
    fun `strict prefix matching rejects them and keeps the real ones`() {
        val strict = PlainPrefixMatcher("ba", true)

        assertTrue(bDashNames.none { strict.prefixMatches(it) }, "none of the b- names start with ba")
        assertTrue(baNames.all { strict.prefixMatches(it) }, "every ba name does")
    }

    @Test
    fun `strict prefix matching is what a package index user expects`() {
        val strict = PlainPrefixMatcher("bas", true)
        assertTrue(strict.prefixMatches("bash"))
        assertTrue(strict.prefixMatches("basedpython"))
        assertFalse(strict.prefixMatches("b-aws-s3-backup"))
        assertFalse(strict.prefixMatches("beautifulsoup4"))
    }

    /**
     * The second defect, and the one that made the list not merely mis-ranked but *missing* the
     * right answers.
     *
     * A query returns at most [PackageNameStore.MAX_RESULTS] names. In a catalogue sorted by PEP 503
     * form, `-` sorts below every letter, so the first fifty names beginning with `b` are all
     * `b-…` — and not one of them begins with `ba`. Filtering that set as the user types can
     * therefore never produce the names they are actually after, which is why the completion has to
     * re-query rather than narrow.
     */
    @Test
    fun `the capped answer for a shorter prefix is not a superset of the longer one`(@TempDir dir: Path) {
        val file = dir.resolve("catalogue.txt")
        // A catalogue shaped like the real one: plenty of `b-` names sorting before any `ba` name.
        val names = (0 until PackageNameStore.MAX_RESULTS * 2).map { "b-pkg-%03d".format(it) } + baNames
        PackageNameStore.write(file, names)
        val store = PackageNameStore(file)

        val forB = store.startingWith("b")
        val forBa = store.startingWith("ba")

        assertEquals(PackageNameStore.MAX_RESULTS, forB.size, "the shorter prefix fills the cap")
        assertTrue(forB.none { it.startsWith("ba") }, "and none of it is what `ba` wants: $forB")
        assertTrue(forBa.isNotEmpty(), "while the catalogue plainly does have `ba` names")
        assertEquals(baNames.sorted(), forBa.sorted())
    }

    /** The store itself was never the problem — it prefix-matches correctly. */
    @Test
    fun `the store returns only names starting with the query`(@TempDir dir: Path) {
        val file = dir.resolve("catalogue.txt")
        PackageNameStore.write(file, bDashNames + baNames)
        val store = PackageNameStore(file)

        assertEquals(baNames.sorted(), store.startingWith("ba").sorted())
        assertTrue(store.startingWith("ba").none { it in bDashNames })
    }
}
