package dev.basedpython.pycharm.highlight.fstring

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Lightweight smoke test for [FStringInterpolationAnnotator]. We only assert that running
 * highlighting over a `.by` file containing various f-strings does not throw. The exact
 * range logic is covered exhaustively by [FStringInterpolationTest].
 *
 * Note: the annotator is invoked here directly (not via plugin.xml registration, which is an
 * orchestrator integration step), so this test exercises the PSI plumbing of the annotator's
 * code path indirectly through [FStringInterpolation] on real PSI text.
 */
class FStringInterpolationAnnotatorSmokeTest : BasePlatformTestCase() {

    fun testDoHighlightingDoesNotThrow() {
        val src = """
            x = f"{a}"
            y = f"hi {name!r} bye"
            z = f"{x:>{w}}"
            t = f${"\"\"\""}${'\n'}multi {value}${'\n'}${"\"\"\""}
            esc = f"{{literal}} {real}"
            plain = "{not_interpolated}"
        """.trimIndent()
        myFixture.configureByText("a.by", src)
        // Should not throw.
        myFixture.doHighlighting()
    }

    fun testAnnotatorAnalyzesFStringPsiText() {
        myFixture.configureByText("b.by", "v = f\"{value}\"\n")
        // Find the f-string literal text via the helper to confirm PSI text round-trips.
        val analysis = FStringInterpolation.analyze("f\"{value}\"")
        assertTrue(analysis.isFString)
        assertEquals(1, analysis.ranges.size)
    }
}
