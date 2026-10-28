package dev.basedpython.pycharm.vcs

import com.intellij.diff.util.DiffUtil
import com.intellij.lang.Language
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.LineTokenizer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
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
@TestFixtures
class ByMergesTest {

    private val fixture by codeInsightFixture()

    /**
     * [base] with its lines [start] to [end] replaced by `by`'s [answer], the way the merge window
     * writes a resolved conflict: the content split into lines, and those lines put in place of the
     * conflict's in its document.
     */
    private fun resolve(base: String, start: Int, end: Int, answer: String): String {
        val document = EditorFactory.getInstance().createDocument(base)
        val content = ByMerges.chunkContent(answer, end == ByMerges.lineCount(base))
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            DiffUtil.applyModification(document, start, end, LineTokenizer.tokenize(content, false).toList())
        }
        return document.text
    }

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

    @Test
    fun `a conflict that ends the file keeps the line break it ends in`() = onEdt {
        // both sides added a function at the end; measured in the merge window of PyCharm 263.7607,
        // which counts the base's lines as 8 and puts the conflict at its line 8, after the empty last
        val base = "def helper() -> int:\n    return 1\n\n\nclass Shape:\n    def area(self) -> float:\n        return 0.0\n"
        val answer = "\ndef ours_fn() -> int:\n    return 2\n\n\ndef theirs_fn() -> int:\n    return 3\n"
        assertEquals(base + "\n\ndef ours_fn() -> int:\n    return 2\n\n\ndef theirs_fn() -> int:\n    return 3\n", resolve(base, 8, 8, answer))
    }

    @Test
    fun `a conflict inside the file is replaced by exactly by's lines`() = onEdt {
        val base = "import os\n\n\ndef where() -> str:\n    return os.getcwd()\n"
        assertEquals("import os\nimport json\nimport sys\n\n\ndef where() -> str:\n    return os.getcwd()\n", resolve(base, 1, 1, "import json\nimport sys\n"))
    }

    @Test
    fun `a file that ends in no line break still ends in none`() = onEdt {
        assertEquals("LIMIT = 1\nLIMIT = 30", resolve("LIMIT = 1\nLIMIT = 20", 1, 2, "LIMIT = 30"))
    }
}
