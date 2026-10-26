package dev.basedpython.pycharm.vcs

import com.intellij.lang.Language
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A merge of `.by` files is resolved by `by/resolveMergeConflicts`, and what a diff ignoring imports
 * and formatting may ignore is `by/diffIgnoredRanges`' ranges, read as offsets into the text.
 */
class ByMergesTest {

    private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = Range(Position(l1, c1), Position(l2, c2))

    @Test
    fun `by's ranges are offsets into the text, in order, kept to it`() {
        val text = "import os\nx = ( 1 +\n  2)\n"
        assertEquals(
            listOf(TextRange(0, 9), TextRange(15, 16), TextRange(19, 22)),
            ByMerges.offsets(text, listOf(range(1, 5, 1, 6), range(0, 0, 0, 9), range(1, 9, 2, 2), range(7, 0, 7, 1))),
        )
    }

    @Test
    fun `only a basedpython file is resolved by by`() {
        assertTrue(ByMergeConflictResolver().isApplicable(BasedPythonLanguage))
        assertFalse(ByMergeConflictResolver().isApplicable(Language.ANY))
    }
}
