package dev.basedpython.pycharm.format

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Asking `buff` is the slow half of *Optimize Imports*, and the platform runs the returned runnable
 * on the EDT inside a write action — so the asking has to be over by the time that runnable exists,
 * and the runnable has to be a pure apply that refuses edits for a document that has moved on.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BuffImportOptimizerTest {

  private val fixture by codeInsightFixture()

  /** `buff`'s answer: drop the first line, computed against [version] of the document at [uri]. */
  private fun dropFirstLine(uri: String, version: Int?): List<Either<Command, CodeAction>> {
    val action = CodeAction("optimize").apply {
      kind = ByCleanupOp.OptimizeImports.kind
      edit = WorkspaceEdit(
        listOf(
          Either.forLeft<TextDocumentEdit, ResourceOperation>(
            TextDocumentEdit(
              VersionedTextDocumentIdentifier(uri, version),
              listOf(TextEdit(Range(Position(0, 0), Position(1, 0)), "")),
            ),
          ),
        ),
      )
    }
    return listOf(Either.forRight(action))
  }

  private fun write(action: () -> Unit) =
    WriteCommandAction.runWriteCommandAction(fixture.project) { action() }

  private fun versionOf(document: Document): Int = FakeBuffClient(fixture.project) { emptyList() }.getDocumentVersion(document)

  @Test
  fun `the server is asked before the runnable exists, and the runnable only applies`() {
    val psi = fixture.configureByText("a.by", "import os\nimport sys\n")
    val document = psi.viewProvider.document
    val client = FakeBuffClient(fixture.project) { dropFirstLine(it.textDocument.uri, versionOf(document)) }
    val optimizer = BuffImportOptimizer { _, _ -> client }

    val runnable = runBlocking { optimizer.processFileSuspend(psi) }
    assertEquals(1, client.requests.get())

    write { runnable.run() }

    assertEquals(1, client.requests.get(), "applying must not ask the server again")
    assertEquals("import sys\n", document.text)
  }

  @Test
  fun `edits the server computed for another version are dropped`() {
    val psi = fixture.configureByText("a.by", "import os\nimport sys\n")
    val document = psi.viewProvider.document
    val client = FakeBuffClient(fixture.project) { dropFirstLine(it.textDocument.uri, versionOf(document) - 1) }

    val edits = runBlocking {
      ByCleanup.requestEdits(client, psi.virtualFile, document, ByCleanupOp.OptimizeImports)
    }

    assertNull(edits)
  }

  @Test
  fun `edits are not applied once the document has moved on since they were asked for`() {
    val psi = fixture.configureByText("a.by", "import os\nimport sys\n")
    val document = psi.viewProvider.document
    // No version from the server: the version the request was sent about still stands guard.
    val client = FakeBuffClient(fixture.project) { dropFirstLine(it.textDocument.uri, null) }
    val optimizer = BuffImportOptimizer { _, _ -> client }

    val runnable = runBlocking { optimizer.processFileSuspend(psi) }
    write { document.insertString(0, "# typed meanwhile\n") }
    write { runnable.run() }

    assertEquals("# typed meanwhile\nimport os\nimport sys\n", document.text)
  }

  @Test
  fun `applying refuses edits stamped with another version`() {
    val psi = fixture.configureByText("a.by", "x\n")
    val document = psi.viewProvider.document
    val client = FakeBuffClient(fixture.project) { emptyList() }
    val stale = ByDocumentEdits(
      listOf(TextEdit(Range(Position(0, 0), Position(0, 1)), "y")),
      client.getDocumentVersion(document) - 1,
    )

    var applied = true
    write { applied = ByCleanup.applyEditsTo(client, document, stale) }

    assertFalse(applied)
    assertEquals("x\n", document.text)
  }
}
