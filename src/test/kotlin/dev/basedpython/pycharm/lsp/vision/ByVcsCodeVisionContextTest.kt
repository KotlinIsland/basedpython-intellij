package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.hints.VcsCodeVisionLanguageContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Which declarations get a code author is `by`'s outline. What is left here is saying so of the
 * right token, in the platform's walk over the flat PSI, and working the author out from the right
 * text.
 */
@TestFixtures
class ByVcsCodeVisionContextTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     * 0 class Shape:
     * 1     sides = 0
     * 2
     * 3     @property
     * 4     def area(self):
     * 5         def helper(): ...
     * 6         return 0
     * ```
     */
    private val source = "class Shape:\n    sides = 0\n\n    @property\n    def area(self):\n        def helper(): ...\n        return 0\n"

    private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
        Range(Position(startLine, startCharacter), Position(endLine, endCharacter))

    private fun symbol(name: String, kind: SymbolKind, range: Range, selection: Range, vararg children: DocumentSymbol) =
        DocumentSymbol(name, kind, range, selection, null, children.toList())

    private val outline = listOf(
        symbol(
            "Shape", SymbolKind.Class, range(0, 0, 6, 16), range(0, 6, 0, 11),
            symbol("sides", SymbolKind.Field, range(1, 4, 1, 13), range(1, 4, 1, 9)),
            symbol(
                "area", SymbolKind.Property, range(3, 4, 6, 16), range(4, 8, 4, 12),
                symbol("helper", SymbolKind.Function, range(5, 8, 5, 25), range(5, 12, 5, 18)),
            ),
        ),
    )

    private fun file(): PsiFile {
        val file = fixture.addFileToProject("pkg/shape.by", source)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        ByDeclarations.getInstance(fixture.project).remember(document, outline)
        return file
    }

    private fun accepted(file: PsiFile): List<String> {
        val context = ByVcsCodeVisionContext()
        return SyntaxTraverser.psiTraverser(file).preOrderDfsTraversal()
            .filter { context.isAccepted(it) }
            .map { "${it.text}@${it.textRange.startOffset}" }
            .toList()
    }

    @Test
    fun `the context is registered for basedpython`() = onEdt {
        assertInstanceOf(
            ByVcsCodeVisionContext::class.java,
            VcsCodeVisionLanguageContext.providersExtensionPoint.forLanguage(BasedPythonLanguage),
        )
    }

    @Test
    fun `each class and function is accepted at the token it starts with, its decorator's for a decorated one`() = onEdt {
        assertEquals(
            listOf(
                "class@0",
                "@@${source.indexOf("@property")}",
                "def@${source.indexOf("def helper")}",
            ),
            accepted(file()),
        )
    }

    @Test
    fun `the author is worked out from the declaration's name to its end`() = onEdt {
        val file = file()
        val decorator = file.findElementAt(source.indexOf("@property"))!!
        val effective = ByVcsCodeVisionContext().computeEffectiveRange(decorator)
        assertEquals("area(self):\n        def helper(): ...\n        return 0", effective.substring(source))
    }

    @Test
    fun `nothing is accepted once the text has moved on from the outline`() = onEdt {
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "\n") }
        PsiDocumentManager.getInstance(fixture.project).commitDocument(document)

        assertEquals(emptyList<String>(), accepted(file))
    }
}
