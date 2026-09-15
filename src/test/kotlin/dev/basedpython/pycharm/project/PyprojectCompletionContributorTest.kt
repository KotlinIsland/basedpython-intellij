package dev.basedpython.pycharm.project

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Each `buff` table in `pyproject.toml` is offered its own keys, and no other table any. */
@TestFixtures
@RunInEdt(writeIntent = true)
class PyprojectCompletionContributorTest {

  private val fixture by codeInsightFixture()

  private fun offered(text: String, fileName: String = "pyproject.toml"): List<String> {
    fixture.configureByText(fileName, text)
    fixture.complete(CompletionType.BASIC)
    return fixture.lookupElementStrings.orEmpty()
  }

  @Test
  fun `format keys in the format table, and not the lint ones`() {
    val keys = offered("[tool.ruff.format]\n<caret>\n")
    assertTrue("quote-style" in keys, keys.toString())
    assertFalse("select" in keys, keys.toString())
  }

  @Test
  fun `lint keys in the lint table, and not the format ones`() {
    val keys = offered("[tool.ruff.lint]\nsel<caret>\n")
    assertTrue("select" in keys || fixture.editor.document.text.contains("select = "), keys.toString())
    assertFalse("quote-style" in keys, keys.toString())
  }

  /** `buff` rejects `quote-style` at the top of `[tool.ruff]`, so it is not offered there. */
  @Test
  fun `the top-level table gets only its own keys`() {
    val keys = offered("[tool.ruff]\n<caret>\n")
    assertTrue("line-length" in keys, keys.toString())
    assertFalse("quote-style" in keys, keys.toString())
    assertFalse("select" in keys, keys.toString())
  }

  @Test
  fun `no buff keys in a table that is not buff's`() {
    val keys = offered("[project]\nname = \"x\"\n<caret>\n")
    assertFalse(keys.any { it in PyprojectCompletionContributor.PyprojectKeyProvider.KEYS.values.flatten() }, keys.toString())
  }

  @Test
  fun `target versions for target-version`() {
    val keys = offered("[tool.ruff]\ntarget-version = <caret>\n")
    assertTrue("\"py312\"" in keys, keys.toString())
  }

  @Test
  fun `table names inside a header`() {
    val keys = offered("[tool.ruff.l<caret>]\n")
    assertTrue("tool.ruff.lint" in keys || fixture.editor.document.text.startsWith("[tool.ruff.lint]"), keys.toString())
  }

  @Test
  fun `nothing in a toml file that is not a pyproject`() {
    val keys = offered("[tool.ruff.format]\n<caret>\n", fileName = "other.toml")
    assertFalse("quote-style" in keys, keys.toString())
  }
}
