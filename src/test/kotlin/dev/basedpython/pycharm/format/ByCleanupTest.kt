package dev.basedpython.pycharm.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `buff`'s edits are LSP ranges; these check where they land on a real document. */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByCleanupTest {

  private val fixture by codeInsightFixture()

  private fun edit(
    startLine: Int,
    startChar: Int,
    endLine: Int,
    endChar: Int,
    newText: String,
  ) = TextEdit(Range(Position(startLine, startChar), Position(endLine, endChar)), newText)

  private fun applied(text: String, vararg edits: TextEdit): String {
    val document = fixture.configureByText("cleanup.by", text).viewProvider.document
    WriteCommandAction.runWriteCommandAction(fixture.project) {
      ByCleanup.applyEditsTo(document, edits.toList())
    }
    return document.text
  }

  /** The shape almost every `buff` answer takes: one edit replacing a run of whole lines. */
  @Test
  fun `replaces a run of whole lines`() {
    assertEquals(
      "import abc\nimport sys\n\nprint(sys.argv)\n",
      applied(
        "import sys\nimport os\nimport abc\n\nprint(sys.argv)\n",
        edit(0, 0, 3, 0, "import abc\nimport sys\n"),
      ),
    )
  }

  /**
   * A range ending one line past the last is how a server says "to the end of the file". There is
   * no such line to ask the document about, so this would be an out-of-bounds offset if it were
   * resolved naively.
   */
  @Test
  fun `an end one line past the last means end of file`() {
    assertEquals(
      "x = 1\n",
      applied("x = 1\ny = 2\n", edit(1, 0, 2, 0, "")),
    )
  }

  /** Deleting the only import leaves the rest of the file alone. */
  @Test
  fun `deletes a single line`() {
    assertEquals(
      "import sys\n\nprint(sys.argv)\n",
      applied(
        "import sys\nimport os\n\nprint(sys.argv)\n",
        edit(1, 0, 2, 0, ""),
      ),
    )
  }

  /**
   * Several edits are applied last-first, because each one's range describes the document as the
   * server saw it. Applied in the given order instead, the second would land at a stale offset.
   */
  @Test
  fun `applies several edits without shifting each other`() {
    assertEquals(
      "AAA\nb\nCCC\n",
      applied(
        "a\nb\nc\n",
        edit(0, 0, 0, 1, "AAA"),
        edit(2, 0, 2, 1, "CCC"),
      ),
    )
  }

  /** A partial-line range is resolved by character offset, not rounded to the line. */
  @Test
  fun `replaces within a line`() {
    assertEquals(
      "x = {\"a\": 1}\n",
      applied("x = {  'a' : 1 }\n", edit(0, 4, 0, 16, "{\"a\": 1}")),
    )
  }
}

/** The order the passes run in is fixed, and it is not the order they were asked for in. */
class ByCleanupOpOrderTest {

  @Test
  fun `formatting runs after the lint pass`() {
    assertEquals(
      listOf(ByCleanupOp.FixAll, ByCleanupOp.FormatAndOptimizeImports),
      ByCleanupOp.inRunOrder(
        listOf(ByCleanupOp.FormatAndOptimizeImports, ByCleanupOp.FixAll),
      ),
    )
  }

  @Test
  fun `only the passes asked for run`() {
    assertEquals(
      listOf(ByCleanupOp.FormatAndOptimizeImports),
      ByCleanupOp.inRunOrder(setOf(ByCleanupOp.FormatAndOptimizeImports)),
    )
    assertEquals(emptyList<ByCleanupOp>(), ByCleanupOp.inRunOrder(emptySet()))
  }

  /** Each toggle names the code action kind the server answers to. */
  @Test
  fun `toggles map to the server's source action kinds`() {
    assertEquals(
      "source.formatAndOptimizeImports.ruff",
      ByCleanupToggle.FormatAndOptimizeImports.op.kind,
    )
    assertEquals("source.fixAll.ruff", ByCleanupToggle.FixAll.op.kind)
    assertEquals("source.optimizeImports.ruff", ByCleanupOp.OptimizeImports.kind)
  }
}
