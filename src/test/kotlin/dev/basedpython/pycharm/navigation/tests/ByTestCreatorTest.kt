package dev.basedpython.pycharm.navigation.tests

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByNewTest
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Create New Test writes what `by` says where `by` says, through the user's templates: nothing about
 * the module name, the import, the test's name or its file is worked out here. What is left is
 * creating the module or adding to it, applying `by`'s import where `by` put it, and never
 * overwriting a module `by` did not know was there.
 */
@TestFixtures
class ByTestCreatorTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `a test module that does not exist is created from the template, importing the code`() = onEdt {
        val root = FileUtil.createTempDirectory("create-test", null, true)
        val target = File(root, "tests/test_circle.by")
        val newTest = ByNewTest(target.toURI().toString(), exists = false, module = "shapes.circle", import = "Circle", testName = "test_circle_area")

        val written = ByTestCreator().write(fixture.project, newTest) { null } as ByTestCreator.Written.Test

        val text = FileDocumentManager.getInstance().getDocument(written.file)!!.text
        assertEquals("from shapes.circle import Circle\n\n\ndef test_circle_area() -> None:\n    ...\n", text)
        assertEquals(text.indexOf("test_circle_area"), written.offset)
        assertEquals(target.canonicalPath, File(written.file.path).canonicalPath)
    }

    @Test
    fun `a module is imported whole`() = onEdt {
        val root = FileUtil.createTempDirectory("create-test", null, true)
        val newTest = ByNewTest(File(root, "tests/test_shapes.by").toURI().toString(), module = "shapes", testName = "test_shapes")

        val written = ByTestCreator().write(fixture.project, newTest) { null } as ByTestCreator.Written.Test

        assertEquals(
            "import shapes\n\n\ndef test_shapes() -> None:\n    ...\n",
            FileDocumentManager.getInstance().getDocument(written.file)!!.text,
        )
    }

    @Test
    fun `to a test module that exists the test is added at the end, with the import by gives`() = onEdt {
        val existing = fixture.addFileToProject("tests/test_circle.by", "import math\n\ndef test_pi() -> None:\n    assert math.pi > 3\n\n")
        val import = TextEdit(Range(Position(1, 0), Position(1, 0)), "from shapes.circle import Circle\n")
        val newTest = ByNewTest(
            existing.virtualFile.url, exists = true, module = "shapes.circle", import = "Circle",
            testName = "test_circle_area", importEdits = listOf(import),
        )

        val written = ByTestCreator().write(fixture.project, newTest) { existing.virtualFile } as ByTestCreator.Written.Test

        val text = FileDocumentManager.getInstance().getDocument(existing.virtualFile)!!.text
        assertEquals(
            "import math\nfrom shapes.circle import Circle\n\ndef test_pi() -> None:\n    assert math.pi > 3\n\n\n" +
                "def test_circle_area() -> None:\n    ...\n",
            text,
        )
        assertEquals(text.indexOf("test_circle_area"), written.offset)
    }

    @Test
    fun `a module that appeared since by looked is left alone`() = onEdt {
        val root = FileUtil.createTempDirectory("create-test", null, true)
        val target = File(root, "tests/test_circle.by").apply { parentFile.mkdirs(); writeText("def test_mine(): ...\n") }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        val newTest = ByNewTest(target.toURI().toString(), exists = false, module = "shapes.circle", import = "Circle", testName = "test_circle")

        val written = ByTestCreator().write(fixture.project, newTest) { null }

        assertEquals(ByTestCreator.Written.Nothing(BasedPythonBundle.message("create.test.appeared", "test_circle.by")), written)
        assertEquals("def test_mine(): ...\n", target.readText())
    }
}
