package dev.basedpython.pycharm.lsp

import com.google.gson.JsonParser
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByTestItem
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which of `by`'s lenses are drawn: not one that runs a test the gutter's icon runs. */
@TestFixtures
class ByTestCodeLensTest {

    private val fixture by codeInsightFixture()

    @AfterEach
    fun forget() = ByProgramModel.getInstance(fixture.project).forgetAll()

    /** The lenses as by 0.0.3 sends them, as lsp4j hands their arguments over. */
    private fun manage(title: String, vararg arguments: String) = lens(
        title,
        "ty.runManageCommand",
        """[{"arguments": [${arguments.joinToString { "\"$it\"" }}], "manage": "/p/manage.py"}]""",
    )

    private fun lens(title: String, command: String, arguments: String?) = CodeLens(RANGE).also {
        it.command = Command(title, command, arguments?.let { json -> JsonParser.parseString(json).asJsonArray.toList() })
    }

    private fun displays(file: VirtualFile, lens: CodeLens) = ByCodeLensSupport(fixture.project).shouldDisplayCodeLens(file, lens)

    private fun file(name: String, items: List<ByTestItem>?): VirtualFile {
        val file = fixture.addFileToProject(name, "class B(TestCase):\n    def test_b(self): pass\n").virtualFile
        items?.let { ByProgramModel.getInstance(fixture.project).rememberTestItems(file, it) }
        return file
    }

    private val item = ByTestItem("B", isClass = true, symbols = listOf("B"), range = RANGE, selectionRange = RANGE, children = emptyList())

    private val tests = listOf(manage("run B", "test", "polls.test_by.B"), manage("run test", "test", "polls.test_by.B.test_b"))

    @Test
    fun `a test lens is not drawn where the gutter runs the file's tests`() = onEdt {
        val file = file("polls/test_by.by", listOf(item))
        for (lens in tests) assertFalse(displays(file, lens), lens.command.title)
    }

    /** Django's `tests.py`, which a `by` that looks for pytest's names lists no test of: the lens is all there is. */
    @Test
    fun `a test lens is drawn where the gutter runs none`() = onEdt {
        val file = file("polls/tests.by", emptyList())
        for (lens in tests) assertTrue(displays(file, lens), lens.command.title)
    }

    @Test
    fun `a test lens is drawn while it is not known what the gutter runs`() = onEdt {
        val file = file("polls/test_new.by", null)
        for (lens in tests) assertTrue(displays(file, lens), lens.command.title)
    }

    @Test
    fun `every other lens is drawn`() = onEdt {
        val file = file("polls/test_by.by", listOf(item))
        val others = listOf(
            manage("migrate to 0001_initial", "migrate", "polls", "0001_initial"),
            manage("show sql", "sqlmigrate", "polls", "0001_initial"),
            manage("make migrations for test", "makemigrations", "test"),
            lens("rendered by blog.views.post", ByCodeLensSupport.SHOW_REFERENCES, """["file:///p/a.html", {"line": 0, "character": 0}, []]"""),
            lens("run B", "ty.runManageCommand", null),
            lens("run B", "ty.runManageCommand", """["test"]"""),
            lens("run B", "ty.runManageCommand", """[{"arguments": []}]"""),
            lens("run B", "ty.runManageCommand", """[{"arguments": [1]}]"""),
            lens("run B", "other", """[{"arguments": ["test"]}]"""),
            CodeLens(RANGE),
        )
        for (lens in others) assertTrue(displays(file, lens), lens.toString())
    }

    private companion object {
        val RANGE = Range(Position(0, 6), Position(0, 7))
    }
}
