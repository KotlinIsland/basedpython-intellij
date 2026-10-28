package dev.basedpython.pycharm.lang

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The editor colours `out` as the variance keyword by what follows it, so an edit after it — the
 * name typed or removed, lines below — has to reach it, though the editor re-reads only from
 * shortly before the edit.
 */
@TestFixtures
class VarianceOutHighlightingTest {

  private val fixture by codeInsightFixture()

  private fun outToken(): IElementType {
    val text = fixture.editor.document.text
    val iterator = (fixture.editor as EditorEx).highlighter.createIterator(text.indexOf("out"))
    return iterator.tokenType
  }

  @Test
  fun `typing the name after out makes it a keyword`() = onEdt {
    fixture.configureByText("a.by", "x: list[out <caret>]\n")
    assertEquals(BasedPythonTokenTypes.IDENTIFIER, outToken())
    fixture.type("int")
    assertEquals(BasedPythonTokenTypes.KEYWORD, outToken())
  }

  @Test
  fun `typing the name lines below out makes it a keyword`() = onEdt {
    fixture.configureByText("a.by", "x: list[\n    out\n    # covariant\n    <caret>\n]\n")
    assertEquals(BasedPythonTokenTypes.IDENTIFIER, outToken())
    fixture.type("int")
    assertEquals(BasedPythonTokenTypes.KEYWORD, outToken())
  }

  @Test
  fun `typing an operand after cast makes out a name`() = onEdt {
    fixture.configureByText("a.by", "y = xs[out cast <caret>]\n")
    assertEquals(BasedPythonTokenTypes.KEYWORD, outToken())
    fixture.type("int")
    assertEquals(BasedPythonTokenTypes.IDENTIFIER, outToken())
  }

  @Test
  fun `deleting the bracket before out makes it a name`() = onEdt {
    fixture.configureByText("a.by", "f <caret>[out T]\n")
    assertEquals(BasedPythonTokenTypes.KEYWORD, outToken())
    fixture.performEditorAction("EditorDelete")
    assertEquals(BasedPythonTokenTypes.IDENTIFIER, outToken())
  }
}
