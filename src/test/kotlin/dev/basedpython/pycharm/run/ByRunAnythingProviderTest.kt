package dev.basedpython.pycharm.run

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.testFramework.asBasedPythonProject
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `by …` in Run Anything: what it completes, from `by`'s answers about the project, and how what
 * follows `by run` is split between `by` and the program.
 */
@TestFixtures
class ByRunAnythingProviderTest {

    private val fixture by codeInsightFixture()

    private fun values(query: String): List<String> {
        val provider = ByRunAnythingProvider()
        val context = SimpleDataContext.getProjectContext(fixture.project)
        return provider.getValues(context, query).map(provider::getCommand)
    }

    @Test
    fun `it completes the subcommands, then the modules by run runs and pytest`() = asBasedPythonProject(fixture.project) {
        val main = fixture.addFileToProject("main.by", "def main(): pass\n").virtualFile
        val model = fixture.addFileToProject("shop/model.by", "x = 1\n").virtualFile
        ByProgramModel.getInstance(fixture.project).rememberModuleNames(mapOf(main to "main", model to "shop.model"))

        assertEquals(listOf("by run", "by check", "by build", "by repl"), values("by "))
        assertEquals(listOf("by run main", "by run pytest", "by run shop.model"), values("by run ").sorted())
    }

    @Test
    fun `it offers nothing outside a basedpython project`() {
        assertEquals(emptyList<String>(), values("by "))
    }

    @Test
    fun `flags before the module are by's and the rest is the program's`() {
        assertEquals(
            listOf("--soundness=none") to listOf("main", "--verbose", "x"),
            ByRunAnythingProvider.byRunParts(listOf("--soundness=none", "main", "--verbose", "x")),
        )
        assertEquals(emptyList<String>() to emptyList<String>(), ByRunAnythingProvider.byRunParts(emptyList()))
    }
}
