package dev.basedpython.pycharm.vcs

import dev.basedpython.pycharm.lsp.ext.ByImportChoice
import dev.basedpython.pycharm.lsp.ext.ByVersionImports
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `by/reconcileMergeImports`' answers, as `by` ebc650899c46 gave them for a real merge, made in the merged text. */
class ByMergeImportsTest {

    private fun range(l1: Int, c1: Int, l2: Int, c2: Int) = Range(Position(l1, c1), Position(l2, c2))

    private fun applied(text: String, edits: List<TextEdit>) = ByMergeImports.apply(text, ByMergeImports.replacements(text, edits)!!)

    @Test
    fun `the import a clean merge dropped is put back`() {
        // ours removed `b()` and `import json`, theirs made `c()` read `json`; git merged without a conflict
        val merged = "def c() -> int:\n    return json.loads(\"3\")\n"
        assertEquals(
            "import json\n\ndef c() -> int:\n    return json.loads(\"3\")\n",
            applied(merged, listOf(TextEdit(range(0, 0, 0, 0), "import json\n\n"))),
        )
    }

    @Test
    fun `a picked import replaces every line binding the name, where by says it belongs`() {
        // each side imported `x` from a different module on a line of its own, and git kept both
        val merged = "from a import x\nimport os\nfrom b import x\n\nZ = os.sep\nW = x\n"
        val choice = ByImportChoice(
            name = "x",
            imports = listOf(ByVersionImports("ours", listOf("from a import x")), ByVersionImports("base"), ByVersionImports("theirs", listOf("from b import x"))),
            position = Position(3, 0),
            bindings = listOf(range(0, 0, 1, 0), range(2, 0, 3, 0)),
        )
        assertEquals("import os\nfrom b import x\n\nZ = os.sep\nW = x\n", applied(merged, ByMergeImports.choiceEdits(choice, listOf("from b import x"))))
        assertEquals("import os\nfrom a import x\n\nZ = os.sep\nW = x\n", applied(merged, ByMergeImports.choiceEdits(choice, listOf("from a import x"))))
    }

    @Test
    fun `an insertion where a removal starts goes before what follows`() {
        val text = "a\nb\nc\n"
        assertEquals("a\nX\nc\n", applied(text, listOf(TextEdit(range(1, 0, 1, 0), "X\n"), TextEdit(range(1, 0, 2, 0), ""))))
    }

    @Test
    fun `edits made for another text are not made`() {
        assertNull(ByMergeImports.replacements("a\n", listOf(TextEdit(range(5, 0, 5, 0), "x"))))
        assertNull(ByMergeImports.replacements("a\n", listOf(TextEdit(range(0, 9, 0, 9), "x"))))
    }

    @Test
    fun `a file one side left alone has nothing to reconcile`() {
        assertFalse(ByMergeVersions("a", "a", "b").bothChanged)
        assertFalse(ByMergeVersions("a", "b", "a").bothChanged)
        assertTrue(ByMergeVersions("a", "b", "c").bothChanged)
    }
}
