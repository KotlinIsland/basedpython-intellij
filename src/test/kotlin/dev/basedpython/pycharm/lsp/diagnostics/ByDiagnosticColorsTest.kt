package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * `by`'s Information diagnostics (`reveal_type`'s *Revealed type*) and untagged Hints drawn as
 * PyCharm's ty integration draws them — a blue wave, a grey bold dotted line — and a Hint tagged
 * `Unnecessary` still grey.
 */
@TestFixtures
class ByDiagnosticColorsTest {

    private val fixture by codeInsightFixture()

    class ThreeDiagnostics : Annotator {
        override fun annotate(element: PsiElement, holder: AnnotationHolder) {
            if (element !is PsiFile) return
            val spec = listOf(
                Triple(0, DiagnosticSeverity.Information, "revealed-type") to emptyList(),
                Triple(2, DiagnosticSeverity.Hint, null) to emptyList(),
                Triple(4, DiagnosticSeverity.Hint, null) to listOf(DiagnosticTag.Unnecessary),
            )
            for ((s, tags) in spec) {
                val (start, severity, code) = s
                val diagnostic = Diagnostic(Range(Position(0, start), Position(0, start + 1)), "d$start").apply {
                    this.severity = severity
                    this.code = code?.let { Either.forLeft(it) }
                    this.tags = tags
                }
                ByDiagnosticsSupport().createAnnotation(holder, diagnostic, TextRange(start, start + 1), emptyList())
            }
        }
    }

    @Test
    fun `information is a blue wave, an untagged hint a grey dotted line, an unnecessary hint grey`() = onEdt {
        val disposable = Disposer.newDisposable()
        try {
            LanguageAnnotators.INSTANCE.addExplicitExtension(BasedPythonLanguage, ThreeDiagnostics(), disposable)
            fixture.configureByText("a.by", "a b c\n")
            val infos = fixture.doHighlighting(HighlightSeverity.INFORMATION).filter { it.description?.startsWith("d") == true }.sortedBy { it.startOffset }
            val scheme = fixture.editor.colorsScheme
            fun look(info: HighlightInfo) = info.getTextAttributes(fixture.file, scheme)

            assertEquals(listOf(HighlightSeverity.WEAK_WARNING, HighlightSeverity.INFORMATION, HighlightSeverity.WEAK_WARNING), infos.map { it.severity })
            assertEquals(EffectType.WAVE_UNDERSCORE, look(infos[0])?.effectType)
            assertEquals(if (scheme.defaultBackground.red < 128) Color(0x589df6) else Color(0x0000ff), look(infos[0])?.effectColor, "${scheme.name}: blue")
            assertEquals(EffectType.BOLD_DOTTED_LINE, look(infos[1])?.effectType)
            assertEquals(Color(0x808080), look(infos[1])?.effectColor)
            assertEquals(scheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES).foregroundColor, look(infos[2])?.foregroundColor)
            assertEquals(null, look(infos[2])?.effectColor, "an unused name: grey, and never underlined")
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
