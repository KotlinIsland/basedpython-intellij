package dev.basedpython.pycharm.navigation.tests

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testIntegration.LanguageTestCreators
import com.intellij.testIntegration.TestFinder
import com.intellij.testIntegration.TestFinderHelper
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.BySubjectTests
import dev.basedpython.pycharm.lsp.ext.ByTestLink
import dev.basedpython.pycharm.navigation.ByDeclarationElement
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByTestItem
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Go to Test decides nothing about which test exercises which code: that is `by/testSubjects` and
 * `by/subjectTests`. What is left here is where in the document to ask, telling `by`'s silence, its
 * not knowing the request and its not answering apart, and turning what it names into elements the
 * platform shows by `by`'s names and goes to.
 */
@TestFixtures
class ByTestLinksTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     * 0 from shapes.circle import Circle
     * 1
     * 2 def test_area() -> None:
     * 3     assert Circle(1.0).area() > 3
     * ```
     */
    private val test = "from shapes.circle import Circle\n\ndef test_area() -> None:\n    assert Circle(1.0).area() > 3\n"

    /**
     * ```
     * 0 class Circle:
     * 1     def area(self) -> float:
     * 2         return 3.14
     * ```
     */
    private val circle = "class Circle:\n    def area(self) -> float:\n        return 3.14\n"

    private fun file(path: String, text: String): PsiFile = fixture.addFileToProject(path, text)

    private fun ask(file: PsiFile, offset: Int, answer: (String) -> CompletableFuture<*>): Pair<ByTestLinksAnswer<List<ByTestLink>>, List<String>> {
        val asked = mutableListOf<String>()
        val client = AnsweringClient(fixture.project) { method -> asked += method; answer(method) }
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val result = ReadAction.computeBlocking<ByTestLinksAnswer<List<ByTestLink>>, RuntimeException> {
            ByTestLinks.testSubjects(client, file.virtualFile, document, file.findElementAt(offset)!!)
        }
        return result to asked
    }

    @Test
    fun `the finder and the creator are registered, so Ctrl+Shift+T is there in a by file`() = onEdt {
        assertTrue(TestFinder.EP_NAME.extensionList.any { it is ByTestFinder })
        assertTrue(LanguageTestCreators.INSTANCE.allForLanguage(BasedPythonLanguage).any { it is ByTestCreator })
    }

    @Test
    fun `with no by to ask, a by file is no source, and the action stays hidden`() = onEdt {
        val file = file("tests/test_circle.by", test)
        assertNull(ByTestFinder().findSourceElement(file.findElementAt(0)!!))
    }

    @Test
    fun `a file is a test when by collects a test from it, and is not before it has said`() = onEdt {
        val tests = file("tests/test_circle.by", test)
        val code = file("src/shapes/circle.by", circle)
        val model = ByProgramModel.getInstance(fixture.project)
        model.rememberTestItems(code.virtualFile, emptyList())
        val area = Range(Position(2, 0), Position(3, 33))
        model.rememberTestItems(
            tests.virtualFile,
            listOf(ByTestItem("test_area", false, listOf("test_area"), area, Range(Position(2, 4), Position(2, 13)), emptyList())),
        )

        assertTrue(TestFinderHelper.isTest(tests.findElementAt(0)))
        assertFalse(TestFinderHelper.isTest(code.findElementAt(0)))
        assertFalse(TestFinderHelper.isTest(file("tests/test_other.by", test).findElementAt(0)))
    }

    @Test
    fun `what by names is shown by its names and gone to at its name`() = onEdt {
        val tests = file("tests/test_circle.by", test)
        val code = file("src/shapes/circle.by", circle)
        val area = ByTestLink(
            "Circle.area", SymbolKind.Method, "shapes.circle", code.virtualFile.url,
            Range(Position(1, 4), Position(2, 19)), Range(Position(1, 8), Position(1, 12)),
        )

        val (answer, asked) = ask(tests, test.indexOf("Circle(1.0)")) { answered(listOf(area)) }

        assertEquals(listOf("by/testSubjects"), asked)
        val links = (answer as ByTestLinksAnswer.Answered).value
        // the light project's files are `temp://` ones, which a descriptor finds by their URL
        val descriptor = object : ProjectWideLspClientDescriptor(fixture.project, "answering") {
            override fun isSupportedFile(file: VirtualFile): Boolean = true
            override fun findFileByUri(fileUri: String): VirtualFile? = VirtualFileManager.getInstance().findFileByUrl(fileUri)
        }
        val target = ReadAction.computeBlocking<ByDeclarationElement?, RuntimeException> {
            ByTestLinks.target(AnsweringClient(fixture.project, descriptor = descriptor) { answered(null) }, links.single())
        }!!
        assertEquals("Circle.area", target.name)
        assertEquals("shapes.circle", target.locationString)
        assertEquals(code, target.containingFile)
        assertEquals(circle.indexOf("area"), target.textOffset)
    }

    @Test
    fun `no test collected from the document, a by without the request and one that fails are three answers`() = onEdt {
        val tests = file("tests/test_circle.by", test)
        assertEquals(ByTestLinksAnswer.Nothing, ask(tests, 0) { answered(null) }.first)
        assertEquals(
            ByTestLinksAnswer.Unsupported,
            ask(tests, 0) { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/testSubjects") }.first,
        )
        assertEquals(ByTestLinksAnswer.Failed, ask(tests, 0) { failed(ResponseErrorCode.InternalError) }.first)
    }

    @Test
    fun `the tests of some code come with where a new one goes`() = onEdt {
        val code = file("src/shapes/circle.by", circle)
        val client = AnsweringClient(fixture.project) {
            answered(BySubjectTests(newTest = dev.basedpython.pycharm.lsp.ext.ByNewTest(module = "shapes.circle", import = "Circle")))
        }
        val answer = ReadAction.computeBlocking<ByTestLinksAnswer<BySubjectTests>, RuntimeException> {
            ByTestLinks.subjectTests(client, code.virtualFile, Position(1, 8))
        }
        assertEquals("shapes.circle", (answer as ByTestLinksAnswer.Answered).value.newTest?.module)
    }

    @Test
    fun `in whitespace, where to ask is where the next thing starts, not where the last one ended`() = onEdt {
        // the blank line between the import and the test belongs to neither, and asking at its start
        // would ask about the import statement it ends
        val tests = file("tests/test_circle.by", test)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(tests)!!
        val blank = tests.findElementAt(test.indexOf("\n\ndef") + 1)!!
        assertEquals(Position(2, 0), ByTestLinks.position(document, blank))
        val name = tests.findElementAt(test.indexOf("test_area") + 2)!!
        assertEquals(Position(2, 4), ByTestLinks.position(document, name))
    }
}
