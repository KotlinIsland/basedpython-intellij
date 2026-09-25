package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.TestApplication
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** How one of `by`'s diagnostics becomes an inspection result, and a Project Errors row. */
@TestApplication
class ByDiagnosticsInspectionTest {

    private val document = DocumentImpl("x: int = ''\nπ = 1\n")

    private fun diagnostic(start: Position, end: Position, severity: DiagnosticSeverity? = DiagnosticSeverity.Error) =
        Diagnostic(Range(start, end), "message", severity, "by")

    @Test
    fun `a range is the text it covers, counted in the document's own units`() {
        // line 1 starts after "x: int = ''\n", 12 characters; "π" is one UTF-16 unit, as LSP counts
        val range = ByDiagnosticsInspection.rangeOf(document, diagnostic(Position(1, 0), Position(1, 1)))
        assertEquals(TextRange(12, 13), range)
        assertEquals("π", range.substring(document.text))
    }

    @Test
    fun `a range past the document's end is clamped to it`() {
        // the server checked a longer text than the document now holds
        val range = ByDiagnosticsInspection.rangeOf(document, diagnostic(Position(1, 4), Position(9, 40)))
        assertEquals(TextRange(16, document.textLength), range)
    }

    @Test
    fun `a point is widened to the character it stands before`() {
        val range = ByDiagnosticsInspection.rangeOf(document, diagnostic(Position(0, 9), Position(0, 9)))
        assertEquals(TextRange(9, 10), range)
    }

    @Test
    fun `each severity is listed as the editor shows it, and a hint is not a problem`() {
        fun severity(s: DiagnosticSeverity?) = ByProjectProblem.severityOf(diagnostic(Position(0, 0), Position(0, 1), s))
        assertEquals(HighlightSeverity.ERROR, severity(DiagnosticSeverity.Error))
        assertEquals(HighlightSeverity.WARNING, severity(DiagnosticSeverity.Warning))
        assertEquals(HighlightSeverity.WEAK_WARNING, severity(DiagnosticSeverity.Information))
        assertNull(severity(DiagnosticSeverity.Hint))

        assertEquals(ProblemHighlightType.GENERIC_ERROR, ByDiagnosticsInspection.highlightTypeOf(HighlightSeverity.ERROR))
        assertEquals(ProblemHighlightType.WARNING, ByDiagnosticsInspection.highlightTypeOf(HighlightSeverity.WARNING))
        assertEquals(ProblemHighlightType.WEAK_WARNING, ByDiagnosticsInspection.highlightTypeOf(HighlightSeverity.WEAK_WARNING))
    }
}
