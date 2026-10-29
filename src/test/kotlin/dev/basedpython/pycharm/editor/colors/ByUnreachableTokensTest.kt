package dev.basedpython.pycharm.editor.colors

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.diagnostics.ByDiagnosticsSupport
import dev.basedpython.pycharm.lsp.semantic.BasedPythonSemanticTokensMapping
import dev.basedpython.pycharm.lsp.semantic.BasedPythonSemanticTokensMapping.keyFor
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.editor.colors.BasedPythonColorSettingsPage
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DiagnosticTag
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unreachable code in its kinds' faded colours: a token `by` marks `unreachable` maps to its kind's
 * unreachable variant, every kind a token can have has one, they are all on the colour page, and
 * the hint over such code draws no colour of its own.
 */
@TestFixtures
class ByUnreachableTokensTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `an unreachable token is its kind's colour, faded`() {
        val unreachable = BasedPythonSemanticTokensMapping.MOD_UNREACHABLE
        assertEquals(ByUnreachableColors.variant(BasedPythonColors.KEYWORD), keyFor("keyword", listOf(unreachable)))
        assertEquals(ByUnreachableColors.variant(BasedPythonHighlightKeys.BUILTIN_NAME), keyFor("function", listOf("defaultLibrary", unreachable)))
        assertEquals(ByUnreachableColors.variant(BasedPythonColors.PARENTHESES), keyFor("punctuation", listOf(unreachable)))
        assertEquals(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES, keyFor("nothingByKnows", listOf(unreachable)), "a kind without a colour: unused code's grey")
    }

    /** Every kind a token can be coloured as, by any type and any one or two modifiers, has a faded variant. */
    @Test
    fun `every kind a token can have has an unreachable variant, on the colour page`() {
        val types = listOf(
            "namespace", "class", "enum", "interface", "struct", "typeParameter", "type", "parameter", "variable",
            "property", "enumMember", "decorator", "event", "function", "method", "macro", "keyword", "modifier",
            "comment", "string", "number", "regexp", "operator", "punctuation", "selfParameter", "clsParameter", "builtinConstant",
        )
        val modifiers = listOf(
            "declaration", "definition", "readonly", "static", "deprecated", "abstract", "async", "modification",
            "documentation", "defaultLibrary", "functionScope", "annotation", "dunder", "keywordArgument",
        )
        val sets = listOf(emptyList<String>()) + modifiers.map { listOf(it) } + modifiers.flatMap { a -> modifiers.map { b -> listOf(a, b) } }
        val keys = types.flatMap { type -> sets.mapNotNull { keyFor(type, it) } }.toSet()
        assertEquals(emptySet<Any>(), keys - ByUnreachableColors.BASES.toSet(), "kinds with no unreachable variant")

        val page = BasedPythonColorSettingsPage().attributeDescriptors.map { it.key }.toSet()
        assertEquals(emptySet<Any>(), ByUnreachableColors.variants.values.toSet() - page, "variants missing from the colour page")
        assertEquals(emptySet<Any>(), ByUnreachableColors.BASES.toSet() - page, "kinds missing from the colour page")
    }

    class UnreachableHint : Annotator {
        override fun annotate(element: PsiElement, holder: AnnotationHolder) {
            if (element !is PsiFile) return
            for ((code, start) in listOf("unreachable-code" to 0, null to 2)) {
                val diagnostic = Diagnostic(Range(Position(0, start), Position(0, start + 1)), "Code is always unreachable").apply {
                    severity = DiagnosticSeverity.Hint
                    tags = listOf(DiagnosticTag.Unnecessary)
                    this.code = code?.let { Either.forLeft(it) }
                }
                ByDiagnosticsSupport().createAnnotation(holder, diagnostic, TextRange(start, start + 1), emptyList())
            }
        }
    }

    @Test
    fun `the unreachable-code hint draws no colour of its own, and keeps its severity`() = onEdt {
        val disposable = Disposer.newDisposable()
        try {
            LanguageAnnotators.INSTANCE.addExplicitExtension(BasedPythonLanguage, UnreachableHint(), disposable)
            fixture.configureByText("a.by", "a b\n")
            val hints = fixture.doHighlighting().filter { it.description == "Code is always unreachable" }.sortedBy { it.startOffset }
            assertEquals(listOf(HighlightSeverity.WEAK_WARNING, HighlightSeverity.WEAK_WARNING), hints.map(HighlightInfo::getSeverity))
            val scheme = fixture.editor.colorsScheme
            val marked = hints[0].getTextAttributes(fixture.file, scheme)
            assertEquals(null, marked?.foregroundColor, "marked tokens: no colour drawn over them")
            assertEquals(null, marked?.effectColor, "and no underline")
            assertEquals(
                scheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES).foregroundColor,
                hints[1].getTextAttributes(fixture.file, scheme)?.foregroundColor,
                "no code, an older by: grey as before",
            )
            assertEquals(null, hints[1].getTextAttributes(fixture.file, scheme)?.effectColor, "and never underlined")
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
