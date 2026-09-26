package dev.basedpython.pycharm.run.model

import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.node.ByTestNodeService
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * How the plugin holds and reads what `by` says about a project's program: answers kept per document
 * revision, the tests they describe, and the files whose changes ask again.
 */
@TestFixtures
class ByProgramModelTest {

    private val fixture by codeInsightFixture()

    private val model get() = ByProgramModel.getInstance(fixture.project)

    @TempDir
    lateinit var dir: Path

    private val nested =
        "import pytest\n\nclass TestA:\n    def test_one(self):\n        assert True\n\n" +
            "def outer():\n    def test_inner():\n        assert False\n\ndef test_top():\n    assert True\n"

    @Test
    fun `an answer is only served for the revision it was given about`() = onEdt {
        val file = fixture.configureByText("test_rev.by", nested)
        model.rememberTestItems(file.virtualFile, ByReplies.testItems(nested))
        assertEquals(2, model.cachedTestItems(file.virtualFile)?.size)

        WriteCommandAction.runWriteCommandAction(fixture.project) {
            fixture.editor.document.insertString(0, "\n")
        }
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()

        assertNull(model.cachedTestItems(file.virtualFile), "an edit must not be answered with what the file said before it")
    }

    @Test
    fun `the item around a position is the innermost test or class`() = onEdt {
        val items = ByReplies.testItems(nested)
        val lines = nested.lines()
        fun at(text: String): ByTestItem? {
            val line = lines.indexOfFirst { text in it }
            return items.innermostAt(line, lines[line].indexOf(text))
        }
        assertEquals(listOf("TestA", "test_one"), at("assert True")?.symbols)
        assertEquals(listOf("TestA"), at("class TestA")?.symbols)
        assertEquals(listOf("test_top"), at("def test_top")?.symbols)
        // Nested in a function, so no test at all — not `TestA::test_inner`.
        assertNull(at("def test_inner"))
        assertNull(at("import pytest"))
    }

    @Test
    fun `a class counts the tests under it`() = onEdt {
        val items = ByReplies.testItems(nested)
        assertEquals(1, items.first { it.isClass }.testCount)
        assertEquals(listOf("TestA", "TestA::test_one", "test_top"), items.walk().map { it.symbols.joinToString("::") }.toList())
    }

    @Test
    fun `an entry point names its lines and its command line`() = onEdt {
        val generic = ByEntryPoint.of(ByReplies.entryPoint("def main[T](name: str):\n    print(name)\n"))
        assertEquals(0, generic.mainLine)
        assertEquals(listOf("name"), generic.commandLine?.required?.map { it.name })

        val invoked = ByEntryPoint.of(ByReplies.entryPoint("def main(a: int):\n    print(a)\n\nmain(1)\n"))
        assertEquals(0, invoked.mainLine)
        assertNull(invoked.commandLine, "a module that calls main itself gets no argument parser")

        val private = ByEntryPoint.of(ByReplies.entryPoint("private def main(a: int):\n    print(a)\n"))
        assertNull(private.mainLine)

        val guard = ByEntryPoint.of(ByReplies.entryPoint("if __name__ == \"__main__\":\n    main()\n"))
        assertEquals(listOf(0), guard.guardLines)
        assertNull(guard.mainLine)
    }

    /**
     * The test tree built from the static answer: node ids as pytest would report them, so running a
     * node and matching its outcome work exactly as they do for a collected one.
     */
    @Test
    fun `the static answer becomes the test tree without running anything`() = onEdt {
        val file = fixture.addFileToProject("tests/test_tree.by", nested)
        model.rememberProjectTests(file.virtualFile, ByReplies.testItems(nested))
        ByBuildOutputs.getInstance(fixture.project).remember(
            file.virtualFile,
            ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/tests/test_tree.py"),
        )
        val service = ByTestNodeService.getInstance(fixture.project)

        service.showStatic()

        val state = service.state as ByTestNodeService.State.Collected
        assertFalse(state.fromPytest)
        val targets = buildList {
            fun walk(node: dev.basedpython.pycharm.run.test.node.ByTestNode) {
                if (node.children.isEmpty()) node.target?.let(::add) else node.children.forEach(::walk)
            }
            walk(state.tree)
        }
        assertTrue(targets.any { it == "tests/test_tree.py::TestA::test_one" }, "targets were $targets")
        assertTrue(targets.any { it == "tests/test_tree.py::test_top" }, "targets were $targets")
        assertFalse(targets.any { "test_inner" in it }, "targets were $targets")
    }

    @Test
    fun `a source or configuration file in the project is a change`() = onEdt {
        val source = local("app.by")
        val config = local("ty.toml")
        assertTrue(touchesProgram(VFileContentChangeEvent(null, source, 0, 1), inContent = { true }))
        assertTrue(touchesProgram(VFileContentChangeEvent(null, config, 0, 1), inContent = { true }))
        // A rename arrives as a property change, and `helper.by` → `test_helper.by` adds tests.
        assertTrue(touchesProgram(VFilePropertyChangeEvent(null, source, VirtualFile.PROP_NAME, "old.by", "app.by"), inContent = { true }))
    }

    /**
     * The VFS topic is application-wide, so another project's saves arrive here too; and a file this
     * project excludes — `out/`, a `.venv` — is not its source. Both are the project's answer to
     * "is this in your content", not a list of directory names that happens to catch most of them.
     */
    @Test
    fun `a file outside the project content is not`() = onEdt {
        val source = local("app.by")
        assertFalse(touchesProgram(VFileContentChangeEvent(null, source, 0, 1), inContent = { false }))
    }

    @Test
    fun `a file that decides nothing is not`() = onEdt {
        val other = local("notes.txt")
        assertFalse(touchesProgram(VFileContentChangeEvent(null, other, 0, 1), inContent = { true }))
        val parent = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir))
        assertFalse(touchesProgram(VFileCreateEvent(null, parent, "__pycache__", true, null, null, null), inContent = { true }))
    }

    @Test
    fun `a blank module is by run's run main, and only an error where by run would refuse it`() = onEdt {
        val configuration = BasedPythonRunConfigurationType.getInstance().runFactory
            .createTemplateConfiguration(fixture.project) as ByRunConfiguration
        configuration.options.module = ""

        configuration.options.programArgs = "--name x"
        val withArguments = assertThrows(RuntimeConfigurationException::class.java) { configuration.checkConfiguration() }
        assertTrue("module" in withArguments.localizedMessage, withArguments.localizedMessage)

        configuration.options.programArgs = ""
        model.rememberModuleNames(emptyMap(), main = "app")
        runCatching { configuration.checkConfiguration() }.exceptionOrNull()?.let {
            assertFalse("run.main" in it.localizedMessage, "a configured run.main is enough: ${it.localizedMessage}")
        }
    }

    private fun local(name: String): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.writeString(dir.resolve(name), "")))
}
