package dev.basedpython.pycharm.transpile

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * The live diff of a `.by` file against its python: one python document rewritten in place while
 * the diff is on screen, and nothing at all watching once it is closed.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class TranspiledPythonDiffTest {

  private val fixture by codeInsightFixture()

  private val transpiles = AtomicInteger()

  private fun diffOf(source: String): Pair<TranspiledPythonDiff, com.intellij.openapi.editor.Document> {
    val psi = fixture.configureByText("a.by", source)
    val diff = TranspiledPythonDiff.create(
      fixture.project,
      psi.virtualFile,
      "python of $source",
      transpile = { _, file ->
        transpiles.incrementAndGet()
        com.intellij.openapi.application.runReadAction {
          "python of ${com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!.text}"
        }
      },
      debounce = 1.milliseconds,
    )!!
    return diff to psi.viewProvider.document
  }

  private fun type(document: com.intellij.openapi.editor.Document, text: String) =
    WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(document.textLength, text) }

  /** Pumps the EDT until [condition] holds, failing after a few seconds. */
  private fun waitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 5_000
    while (!condition()) {
      check(System.currentTimeMillis() < deadline) { "timed out" }
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      Thread.sleep(5)
    }
  }

  /** Lets a refresh that should not happen have every chance to. */
  private fun settle() {
    repeat(20) {
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      Thread.sleep(5)
    }
  }

  @Test
  fun `the python side is rewritten in place while the diff is shown`() {
    val (diff, source) = diffOf("x = 1\n")
    val python = diff.pythonDocument

    diff.onAssigned(true)
    type(source, "y = 2\n")

    waitUntil { python.text == "python of x = 1\ny = 2\n" }
    assertTrue(python.isWritable.not(), "the python side stays read-only to the user")
    diff.onAssigned(false)
  }

  @Test
  fun `nothing is watched before the diff is shown or after it is closed`() {
    val (diff, source) = diffOf("x = 1\n")
    assertFalse(diff.isWatching)

    type(source, "a = 0\n")
    settle()
    assertEquals(0, transpiles.get())

    diff.onAssigned(true)
    assertTrue(diff.isWatching)
    diff.onAssigned(false)
    assertFalse(diff.isWatching)

    type(source, "b = 0\n")
    settle()
    assertEquals(0, transpiles.get(), "a closed diff must not keep transpiling")
    assertEquals("python of x = 1\n", diff.pythonDocument.text)
  }

  /** The same request can be on screen twice; it keeps watching until the last viewer lets go. */
  @Test
  fun `watching lasts until every viewer has let go`() {
    val (diff, _) = diffOf("x = 1\n")

    diff.onAssigned(true)
    diff.onAssigned(true)
    diff.onAssigned(false)
    assertTrue(diff.isWatching)
    diff.onAssigned(false)
    assertFalse(diff.isWatching)
  }
}
