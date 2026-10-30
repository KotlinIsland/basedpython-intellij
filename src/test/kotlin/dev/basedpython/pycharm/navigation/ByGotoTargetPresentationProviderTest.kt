package dev.basedpython.pycharm.navigation

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.ByRangeElement
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.lsp.ext.ByDeclarationParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `.by` target in a Go to popup is drawn as `by/declaration` names it — name, kind and what declares
 * it — where the platform's LSP navigation gave every one the file's name: `area (shapes.by)` three
 * times for `Shape.area`'s implementations (measured in PyCharm 263.7649).
 */
@TestFixtures
class ByGotoTargetPresentationProviderTest {

    private val fixture by codeInsightFixture()

    private val source = "class Shape:\n    def area(self) -> float:\n        return 0.0\n\n\nclass Square(Shape):\n    def area(self) -> float:\n        return 1.0\n"

    /** A target where the platform's LSP navigation puts one: an empty range at the name. */
    private fun target(file: PsiFile, offset: Int): PsiElement = ByRangeElement(file, TextRange.from(offset, 0))

    private fun declaration(kind: String?, qualifiedName: String?) =
        ByDeclaration(kind = kind, name = "area", qualifiedName = qualifiedName)

    @Test
    fun `an implementation is named and placed in its class, with its kind's icon`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        val offset = source.lastIndexOf("area")
        val client = AnsweringClient(fixture.project) { answered(declaration("method", "pkg.shapes.Square.area")) }

        val presentation = withContext(Dispatchers.Default) {
            readAction { ByGotoTargetPresentationProvider().presentation(target(file, offset), client) }
        }!!

        assertEquals("area", presentation.presentableText)
        assertEquals("pkg.shapes.Square", presentation.containerText)
        assertEquals(AllIcons.Nodes.Method, presentation.icon)
        // asked at the target, the name of the second `def`
        assertEquals(Position(6, 8), (client.sentParams.single() as ByDeclarationParams).position)
    }

    @Test
    fun `a declaration with no dotted name is placed in its file`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        val client = AnsweringClient(fixture.project) { answered(declaration("function", null)) }

        val presentation = withContext(Dispatchers.Default) {
            readAction { ByGotoTargetPresentationProvider().presentation(target(file, source.indexOf("area")), client) }
        }!!

        assertEquals("area", presentation.presentableText)
        assertEquals("shapes.by", presentation.containerText)
    }

    @Test
    fun `a by without the request, or no answer, leaves the target to the platform`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }
        val nothing = AnsweringClient(fixture.project) { answered(null) }

        withContext(Dispatchers.Default) {
            readAction {
                assertNull(ByGotoTargetPresentationProvider().presentation(target(file, source.indexOf("area")), old))
                assertNull(ByGotoTargetPresentationProvider().presentation(target(file, source.indexOf("area")), nothing))
            }
        }
    }

    @Test
    fun `it is asked before the platform's own`() {
        val providers = ExtensionPointName<Any>("com.intellij.gotoTargetPresentationProvider").extensionList
        assertTrue(providers.first() is ByGotoTargetPresentationProvider, providers.toString())
    }
}
