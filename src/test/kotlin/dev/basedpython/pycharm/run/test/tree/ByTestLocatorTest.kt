package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.PsiLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByReplies
import dev.basedpython.pycharm.run.test.node.ByTestNodeActions
import dev.basedpython.pycharm.run.test.node.ByTestSource
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Going from a test in a run's tree, or in the node view, to the declaration it came from.
 *
 * Both take a pytest node id naming the tree `by run` staged, find the source through what
 * `by/buildOutput` said it is staged as, and land on the name `by/testItems` gave the test — never a
 * search of the text for something that looks like its declaration.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTestLocatorTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    /**
     * `test_top` is declared twice: nested in `outer`, which pytest does not collect, and at the top
     * level, which it does. A search for the first `def test_top` lands on the nested one.
     */
    private val source = """
        def outer():
            def test_top():
                assert False


        class TestGroup:
            def test_in_class(self):
                assert True


        def test_top():
            assert True

    """.trimIndent()

    private fun addStagedTestFile(path: String, staged: String): PsiFile =
        fixture.addFileToProject(path, source).also {
            val items = ByReplies.testItems(source)
            ByProgramModel.getInstance(project).rememberTestItems(it.virtualFile, items)
            ByProgramModel.getInstance(project).rememberProjectTests(it.virtualFile, items)
            ByBuildOutputs.getInstance(project).remember(
                it.virtualFile,
                ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/$staged"),
            )
        }

    private fun locate(nodeId: String): Pair<PsiFile, Int>? {
        val location = ByTestLocator.getLocation(
            ByTestLocator.PROTOCOL,
            nodeId,
            project,
            GlobalSearchScope.allScope(project),
        ).singleOrNull() as? PsiLocation<*> ?: return null
        val element = location.psiElement
        return element.containingFile to element.textRange.startOffset
    }

    @Test
    fun `a test lands on the declaration pytest collects, not the first one spelled like it`() {
        val file = addStagedTestFile("test_located.by", "test_located.py")
        val (found, offset) = checkNotNull(locate("test_located.py::test_top"))
        assertEquals(file.virtualFile, found.virtualFile)
        assertEquals(source.lastIndexOf("test_top"), offset)
    }

    @Test
    fun `a method lands inside its class`() {
        addStagedTestFile("test_method.by", "test_method.py")
        val (_, offset) = checkNotNull(locate("test_method.py::TestGroup::test_in_class"))
        assertEquals(source.indexOf("test_in_class"), offset)
    }

    /** `src/tests/test_srclayout.by` is staged as `tests/test_srclayout.py`, which is what pytest reports. */
    @Test
    fun `a src-layout test is found through where by stages it`() {
        val file = addStagedTestFile("src/tests/test_srclayout.by", "tests/test_srclayout.py")
        val (found, offset) = checkNotNull(locate("tests/test_srclayout.py::test_top[1-2]"))
        assertEquals(file.virtualFile, found.virtualFile)
        assertEquals(source.lastIndexOf("test_top"), offset)
    }

    @Test
    fun `the node view opens the same place`() {
        val file = addStagedTestFile("src/tests/test_opened.by", "tests/test_opened.py")
        assertTrue(ByTestNodeActions.navigate(project, "tests/test_opened.py::test_top", ByTestSource.TRANSPILED))
        val editor = checkNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
        assertEquals(file.virtualFile, editor.virtualFile)
        assertEquals(source.lastIndexOf("test_top"), editor.caretModel.offset)
    }
}
