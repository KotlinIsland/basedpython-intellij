package dev.basedpython.pycharm.lsp.supers

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByDeclarationUsages
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The gutter's *is subclassed*, *is overridden* and *is implemented* icons, drawn from the
 * implementations count of `by/documentUsageCounts` — which is all they need, where the platform's
 * own asked a request per declaration.
 */
@TestFixtures
class ByInheritorMarkersTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     * 0 class Shape:
     * 1     def area(self): ...
     * 2     def name(self): ...
     * 3     def size(self): ...
     * ```
     */
    private val source = "class Shape:\n    def area(self): ...\n    def name(self): ...\n    def size(self): ...\n"

    private fun name(line: Int, start: Int, length: Int) = Range(Position(line, start), Position(line, start + length))

    private fun declaration(name: String, kind: String, line: Int, start: Int, implementations: Int, abstract: Boolean = false) =
        ByDeclarationUsages(name, kind, name(line, 0, 20), name(line, start, name.length), implementations = implementations, abstract = abstract)

    @Test
    fun `each declaration with implementations gets its icon on its name, and one with none gets nothing`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toHashSet<PsiElement>()
        val markers = ByInheritorMarkers.markers(
            file,
            document,
            listOf(
                declaration("Shape", "class", 0, 6, implementations = 2),
                declaration("area", "method", 1, 8, implementations = 3, abstract = true),
                declaration("name", "method", 2, 8, implementations = 1),
                declaration("size", "method", 3, 8, implementations = 0),
            ),
            leaves,
        )
        assertEquals(
            listOf(
                Triple("Shape", AllIcons.Gutter.OverridenMethod, "Is subclassed by 2 classes"),
                Triple("area", AllIcons.Gutter.ImplementedMethod, "Is implemented in 3 subclasses"),
                Triple("name", AllIcons.Gutter.OverridenMethod, "Is overridden in 1 subclass"),
            ),
            markers.map { Triple(it.element!!.text, it.icon, it.lineMarkerTooltip) },
        )
    }
}
