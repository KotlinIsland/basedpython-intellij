package dev.basedpython.pycharm.run.test.node

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByReplies
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The tree [ByTestNodeService] builds from the server's static answer, and the node ids it gives the
 * tests in it — which are what a run is launched with and what its outcomes come back keyed by.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByTestNodeServiceTest {

    private val fixture by codeInsightFixture()

    /**
     * A src-layout project's `src/tests/test_listed.by` is staged by `by run` as
     * `tests/test_listed.py`, so that is the node id pytest reports and the target a run needs —
     * taken from `by/buildOutput`, not from the source's path.
     */
    @Test
    fun `a src-layout test is listed under the path by stages it at`() {
        val source = "def test_addition():\n    assert 1 + 1 == 2\n"
        val file = fixture.addFileToProject("src/tests/test_listed.by", source).virtualFile
        ByProgramModel.getInstance(fixture.project).rememberProjectTests(file, ByReplies.testItems(source))
        ByBuildOutputs.getInstance(fixture.project).remember(
            file,
            ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/tests/test_listed.py"),
        )

        val service = ByTestNodeService.getInstance(fixture.project)
        service.showStatic()

        val tree = (service.state as ByTestNodeService.State.Collected).tree
        val tests = generateSequence(listOf(tree)) { level -> level.flatMap { it.children }.ifEmpty { null } }
            .flatten()
            .filter { it.kind == ByTestNodeKind.TEST }
            .toList()
        val test = tests.single { it.name == "test_addition" && it.target?.contains("test_listed") == true }
        assertEquals("tests/test_listed.py::test_addition", test.target)
        val fileNode = generateSequence(listOf(tree)) { level -> level.flatMap { it.children }.ifEmpty { null } }
            .flatten()
            .single { it.kind == ByTestNodeKind.FILE && it.target == "tests/test_listed.py" }
        assertEquals("test_listed.by", fileNode.name)
    }
}
