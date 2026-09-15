package dev.basedpython.pycharm.inspections.explain

import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.diagnostics.ByDiagnosticsSupport
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *Explain rule* takes its code from the diagnostic the server sent, so it works for every code
 * either tool has — including `by`'s kebab-case ones, which the pattern this replaced
 * (`[A-Z]{1,4}\d{2,4}`) could never match.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ExplainRuleFixTest {

  private val fixture by codeInsightFixture()

  private fun diagnostic(code: Either<String, Int>?) =
    Diagnostic(Range(Position(0, 0), Position(0, 1)), "message").apply {
      severity = DiagnosticSeverity.Warning
      this.code = code
    }

  @Test
  fun `the code is read off the diagnostic in either spelling`() {
    assertEquals("invalid-argument-type", ExplainRuleFix.codeOf(diagnostic(Either.forLeft("invalid-argument-type"))))
    assertEquals("F401", ExplainRuleFix.codeOf(diagnostic(Either.forLeft("F401"))))
    assertEquals("7", ExplainRuleFix.codeOf(diagnostic(Either.forRight(7))))
    assertNull(ExplainRuleFix.codeOf(diagnostic(null)))
  }

  /** Stands in for the platform's LSP annotator: hands the diagnostics support one diagnostic per file. Built by reflection, hence no arguments. */
  class OneDiagnostic : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
      if (element !is PsiFile) return
      val diagnostic = Diagnostic(Range(Position(0, 0), Position(0, 1)), "message").apply {
        severity = DiagnosticSeverity.Warning
        this.code = Either.forLeft("invalid-argument-type")
      }
      ByDiagnosticsSupport().createAnnotation(holder, diagnostic, TextRange(0, 1), emptyList())
    }
  }

  @Test
  fun `each diagnostic offers to explain its own rule, and the action finds it under the caret`() {
    val disposable = Disposer.newDisposable()
    try {
      LanguageAnnotators.INSTANCE.addExplicitExtension(BasedPythonLanguage, OneDiagnostic(), disposable)
      fixture.configureByText("a.by", "<caret>x = 1\n")

      fixture.doHighlighting()

      assertTrue(
        fixture.availableIntentions.any { it.text == "Explain rule invalid-argument-type" },
        fixture.availableIntentions.map { it.text }.toString(),
      )
      assertEquals(listOf("invalid-argument-type"), ExplainRuleFix.codesAt(fixture.project, fixture.editor, 0))
    } finally {
      Disposer.dispose(disposable)
    }
  }
}
