package dev.basedpython.pycharm.navigation

import com.intellij.ide.actions.QualifiedNameProviderUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonFile
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
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Copy Reference in a `.by` editor copies the dotted name `by/declaration` gives the name at the
 * caret, and highlights that name; a local, or no answer, leaves the platform's `path:line`.
 */
@TestFixtures
class ByCopyReferenceActionTest {

    private val fixture by codeInsightFixture()

    private val source = "from shapes import Circle\n\nc = Circle(3)\nc.area()\n"

    private fun declaration(qualifiedName: String?) = ByDeclaration(
        kind = "method",
        name = "area",
        qualifiedName = qualifiedName,
        originSelectionRange = Range(Position(3, 2), Position(3, 6)),
        uri = "file:///shapes.by",
        range = Range(Position(4, 4), Position(5, 26)),
        selectionRange = Range(Position(4, 8), Position(4, 12)),
    )

    @Test
    fun `the platform's Copy Reference is this one`() = onEdt {
        assertInstanceOf(ByCopyReferenceAction::class.java, ActionManager.getInstance().getAction("CopyReference"))
    }

    @Test
    fun `the name at the caret is copied as by names it, and is what is highlighted`() = onEdt {
        val psi = fixture.configureByText("main.by", source) as BasedPythonFile
        fixture.editor.caretModel.moveToOffset(source.indexOf("area") + 1)
        val client = AnsweringClient(fixture.project) { answered(declaration("shapes.base.Circle.area")) }

        // on the EDT, as the action runs, and in the background read action the popup updates it in
        val onEdt = ByCopyReferenceAction().declarationAt(fixture.editor, psi, psi.virtualFile, client)!!
        val inBackground = withContext(Dispatchers.Default) {
            readAction { ByCopyReferenceAction().declarationAt(fixture.editor, psi, psi.virtualFile, client) }
        }!!

        for (element in listOf(onEdt, inBackground)) {
            assertEquals("shapes.base.Circle.area", QualifiedNameProviderUtil.getQualifiedName(element))
            assertEquals(TextRange.from(source.indexOf("area"), 4), element.textRange)
        }
        assertEquals(Position(3, 3), (client.sentParams.first() as ByDeclarationParams).position)
    }

    @Test
    fun `a name local to a function has no dotted name, so path and line are copied`() = onEdt {
        val psi = fixture.configureByText("main.by", source) as BasedPythonFile
        fixture.editor.caretModel.moveToOffset(source.indexOf("area"))
        val client = AnsweringClient(fixture.project) { answered(declaration(null)) }

        val element = ByCopyReferenceAction().declarationAt(fixture.editor, psi, psi.virtualFile, client)!!
        assertNull(QualifiedNameProviderUtil.getQualifiedName(element))
    }

    @Test
    fun `a by without the request, or no name at the caret, leaves the platform's elements`() = onEdt {
        val psi = fixture.configureByText("main.by", source) as BasedPythonFile
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }
        val nothing = AnsweringClient(fixture.project) { answered(null) }

        assertNull(ByCopyReferenceAction().declarationAt(fixture.editor, psi, psi.virtualFile, old))
        assertNull(ByCopyReferenceAction().declarationAt(fixture.editor, psi, psi.virtualFile, nothing))
    }
}
