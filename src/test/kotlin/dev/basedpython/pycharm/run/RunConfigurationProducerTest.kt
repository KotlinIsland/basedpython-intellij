package dev.basedpython.pycharm.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.run.main.ByMainArgumentHistory
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByReplies
import dev.basedpython.pycharm.run.test.ByPytest
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the context-based run configuration producers end-to-end through real PSI files
 * created by the fixture. These are the code paths the gutter "run" icons drive via
 * [RunConfigurationProducer], so a failure here means the icons silently produce the wrong
 * (or no) configuration.
 */
@TestFixtures
class RunConfigurationProducerTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    /** Builds a [ConfigurationContext] anchored at the first leaf of [file]. */
    private fun contextFor(file: PsiFile): ConfigurationContext {
        val element = file.findElementAt(0) ?: file
        return ConfigurationContext(element)
    }

    private val model get() = ByProgramModel.getInstance(project)

    /**
     * Adds a file whose module name `by run` resolves to [module] — the answer `by/runModules` gives,
     * put in as though the server had just given it. Which name a file has is the server's to decide
     * (`run_modules_resolve_as_by_run_does` in `ty_server`); what is under test here is that the
     * producer builds its configuration from that answer.
     */
    private fun addModule(path: String, text: String, module: String): PsiFile =
        fixture.addFileToProject(path, text).also { model.rememberModuleNames(mapOf(it.virtualFile to module)) }

    /**
     * Adds a test file, with the tests `by/testItems` finds in [text] ([ByReplies]) already known, and
     * `by/buildOutput`'s answer that it is staged at [staged].
     */
    private fun addTestFile(path: String, text: String, staged: String = path.removeSuffix(".by") + ".py"): PsiFile =
        fixture.addFileToProject(path, text).also {
            val items = ByReplies.testItems(text)
            model.rememberTestItems(it.virtualFile, items)
            model.rememberProjectTests(it.virtualFile, items)
            rememberStaged(it, staged)
        }

    /** Puts in `by/buildOutput`'s answer that [file] is written to [staged] within the build. */
    private fun rememberStaged(file: PsiFile, staged: String) =
        ByBuildOutputs.getInstance(project).remember(
            file.virtualFile,
            ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/$staged"),
        )

    private inline fun <reified T : RunConfigurationProducer<*>> producer(): T =
        RunConfigurationProducer.getInstance(T::class.java)

    /**
     * Runs [body] with the project marked basedpython and `.py` pinned to this plugin, then puts
     * both back — the marker is a real file at the project base, and the ownership choice is a
     * persisted setting, so leaving either behind would leak into the tests after it.
     *
     * Pinned rather than left on AUTO so the outcome does not depend on whether the IDE running the
     * tests happens to provide the Python language.
     */
    private fun asBasedPythonProject(body: () -> Unit) = onEdt {
        dev.basedpython.pycharm.testFramework.asBasedPythonProject(project, body)
    }

    // ------------------------------------------------------------------
    // by run
    // ------------------------------------------------------------------

    @Test
    fun `by run producer builds module name from by file`() = onEdt {
        val file = addModule("pkg/main.by", "if __name__ == \"__main__\":\n    main()\n", "pkg.main")
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by run producer should produce a configuration for a .by file")
        val config = fromContext!!.configuration as ByRunConfiguration
        assertEquals("pkg.main", config.options.module)
        assertEquals("pkg.main", config.name)
    }

    @Test
    fun `a context configuration is seeded with what the module was last run with`() = onEdt {
        // This is what keeps the argument prompt to once per program: the gutter's plain Run picks
        // up the arguments the form was last given, instead of starting bare and failing again.
        ByMainArgumentHistory.remember(project, "seeded.main", "--name bob")
        val file = addModule("seeded/main.by", "def main(name: str):\n    print(name)\n", "seeded.main")
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by run producer should produce a configuration for a .by file")
        val config = fromContext!!.configuration as ByRunConfiguration
        assertEquals("--name bob", config.options.programArgs)
    }

    /**
     * A `.py` the plugin does not own is PyCharm's, and offering a second configuration on it would
     * be two green arrows on one file. The fixture project carries no basedpython marker, so this
     * is that case.
     */
    @Test
    fun `by run producer ignores a py file it does not own`() = onEdt {
        val file = fixture.configureByText("main.py", "print(1)\n")
        model.rememberModuleNames(mapOf(file.virtualFile to "main"))
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNull(fromContext, "by run producer should not fire on a .py it does not own")
    }

    /**
     * A `.py` the plugin *does* own is a module `by run` can start: `by run` transpiles only `.by`,
     * so the interpreter imports this file from where it was written.
     */
    @Test
    fun `by run producer builds a module name from an owned py file`() = asBasedPythonProject {
        val file = addModule("pkg/script.py", "print(1)\n", "pkg.script")
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by run producer should produce a configuration for an owned .py")
        assertEquals("pkg.script", (fromContext!!.configuration as ByRunConfiguration).options.module)
    }

    /**
     * A file the server lists no module name for — one of two files that build to one module, say —
     * is one a configuration produced from would run the other file of. So it produces nothing.
     */
    @Test
    fun `a file by run names nothing produces nothing`() = asBasedPythonProject {
        addModule("twin.py", "print(1)\n", "twin")
        val unnamed = fixture.addFileToProject("twin.by", "print(2)\n")
        assertNull(
            producer<ByRunFromFileProducer>().createConfigurationFromContext(contextFor(unnamed)),
            "a file with no name of its own should not offer to run the file that holds it",
        )
        assertNotNull(
            producer<ByRunFromFileProducer>()
                .createConfigurationFromContext(contextFor(addModule("other.by", "x = 1\n", "other"))),
            "the .by itself is unaffected",
        )
    }

    // ------------------------------------------------------------------
    // by check
    // ------------------------------------------------------------------

    @Test
    fun `by check producer sets path from by file`() = onEdt {
        val file = fixture.addFileToProject("pkg/main.by", "x = 1\n")
        val fromContext = producer<ByCheckFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by check producer should produce a configuration for a .by file")
        val config = fromContext!!.configuration as ByCheckConfiguration
        assertTrue(
            config.options.paths.contains("main.by"),
            "check path should reference the file, was '${config.options.paths}'",
        )
    }

    // ------------------------------------------------------------------
    // pytest (gutter "Run test" icon must resolve to a ByTestConfiguration)
    // ------------------------------------------------------------------

    @Test
    fun `the pytest producer targets a top-level test function`() = onEdt {
        val file = addTestFile("test_thing.by", "def test_addition():\n    assert 1 + 1 == 2\n")
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "the pytest producer should fire on a `def test_…` line")
        val config = fromContext!!.configuration as ByTestConfiguration
        assertEquals("test_thing.py::test_addition", config.options.paths)
        assertEquals("pytest test_thing.py::test_addition", config.name)
    }

    /**
     * `by run` stages a src-layout project's `src/tests/test_staged.by` as `tests/test_staged.py`:
     * the path inside the staged tree follows the module tree, not the directory tree. The target is
     * where `by/buildOutput` says the file lands — `by run` and `by build` lay a source out with the
     * same `transpiled_destination` — and not the source's own path with its extension swapped.
     */
    @Test
    fun `a src-layout test is targeted where by stages it`() = onEdt {
        val file = addTestFile(
            "src/tests/test_staged.by",
            "def test_addition():\n    assert 1 + 1 == 2\n",
            staged = "tests/test_staged.py",
        )
        val config = producer<ByTestFromFileProducer>().createConfigurationFromContext(contextFor(file))
            ?.configuration as? ByTestConfiguration
        assertNotNull(config)
        assertEquals("tests/test_staged.py::test_addition", config!!.options.paths)
        assertEquals(
            listOf("--in-build", "pytest", "-v", "tests/test_staged.py::test_addition"),
            ByPytest.arguments(config.options.paths),
        )
    }

    /** Until `by` has said where a file is staged, there is no target to give pytest, and no guess is made. */
    @Test
    fun `a test file by has not placed produces nothing`() = onEdt {
        val file = fixture.addFileToProject("tests/test_unplaced.by", "def test_addition():\n    assert 1 + 1 == 2\n")
        val items = ByReplies.testItems(file.text)
        model.rememberTestItems(file.virtualFile, items)
        model.rememberProjectTests(file.virtualFile, items)
        assertNull(producer<ByTestFromFileProducer>().createConfigurationFromContext(contextFor(file)))
    }

    @Test
    fun `the pytest producer qualifies a method with its enclosing class`() = onEdt {
        val file = addTestFile("test_thing.by", "class TestMath:\n    def test_add(self):\n        assert True\n")
        // Anchor the context on the `def test_add` line (second line).
        val offset = file.text.indexOf("def test_add")
        val element = file.findElementAt(offset) ?: file
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(ConfigurationContext(element))
        assertNotNull(fromContext, "the pytest producer should fire on a nested `def test_…` method")
        val config = fromContext!!.configuration as ByTestConfiguration
        assertEquals("test_thing.py::TestMath::test_add", config.options.paths)
    }

    @Test
    fun `the pytest producer ignores a file with no tests`() = onEdt {
        val file = addTestFile("test_plain.by", "x = 1\n")
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNull(fromContext, "the pytest producer should not fire where there is no test")
        assertNull(
            producer<ByTestFromFileProducer>().createConfigurationFromContext(ConfigurationContext(file)),
            "nor on the file as a whole",
        )
    }

    /**
     * Right-clicking a test file anywhere outside a test — its imports, or the file itself in the
     * project view — runs the whole file, rather than falling through to `by run tests.test_math`.
     */
    @Test
    fun `a test file outside any test is run whole`() = onEdt {
        val source =
            "import pytest\n\nclass TestA:\n    def test_one(self):\n        assert True\n\n" +
                "def outer():\n    def test_inner():\n        assert False\n\ndef test_top():\n    assert True\n"
        val file = addTestFile("tests/test_whole.by", source)
        for (context in listOf(contextFor(file), ConfigurationContext(file))) {
            val config = producer<ByTestFromFileProducer>().createConfigurationFromContext(context)
                ?.configuration as? ByTestConfiguration
            assertNotNull(config, "a test file should be runnable as a whole")
            assertEquals("tests/test_whole.py", config!!.options.paths)
        }
    }

    /**
     * A `def test_…` nested in a function is not a test pytest collects, whatever class came before
     * it — so a context inside it is inside the enclosing function, which is no test, and the file is
     * what runs.
     */
    @Test
    fun `a test nested in a function is not qualified with the class above it`() = onEdt {
        val source =
            "import pytest\n\nclass TestA:\n    def test_one(self):\n        assert True\n\n" +
                "def outer():\n    def test_inner():\n        assert False\n\ndef test_top():\n    assert True\n"
        val file = addTestFile("tests/test_nested.by", source)
        val element = file.findElementAt(source.indexOf("test_inner"))!!
        val config = producer<ByTestFromFileProducer>().createConfigurationFromContext(ConfigurationContext(element))
            ?.configuration as? ByTestConfiguration
        assertNotNull(config)
        assertEquals("tests/test_nested.py", config!!.options.paths)
    }

    @Test
    fun `gutter context on a test line resolves to a test config`() = onEdt {
        val file = addTestFile("test_thing.by", "def test_addition():\n    assert 1 + 1 == 2\n")
        val context = contextFor(file)
        val produced = RunConfigurationProducer.getProducers(project)
            .mapNotNull { it.createConfigurationFromContext(context)?.configuration }
        val testConfig = produced.filterIsInstance<ByTestConfiguration>().firstOrNull()
        assertNotNull(
            testConfig,
            "a .by test line should yield a test configuration; produced=${produced.map { it::class.simpleName }}",
        )
    }

    // ------------------------------------------------------------------
    // Precedence: test > run > check
    //
    // `by run` and `by check` both match every .by file. With nothing arbitrating, a context run
    // (Ctrl+Shift+R) offers a chooser — and that chooser labels entries by configuration *type*,
    // which is the same type for both factories, so it reads "basedpython" twice with no way to
    // tell them apart.
    // ------------------------------------------------------------------

    @Test
    fun `by run takes precedence over by check on a plain by file`() = onEdt {
        val file = addModule("pkg/app.by", "x = 1\n", "pkg.app")
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val check = producer<ByCheckFromFileProducer>().createConfigurationFromContext(context)
        assertNotNull(run, "by run should match a plain .by file")
        assertNotNull(check, "by check should also match it — that is the ambiguity")

        assertTrue(
            producer<ByRunFromFileProducer>().isPreferredConfiguration(run, check),
            "by run must be preferred over by check, or a context run is ambiguous",
        )
        assertTrue(
            producer<ByRunFromFileProducer>().shouldReplace(run!!, check!!),
            "by run must replace by check, or the platform shows an unreadable chooser",
        )
    }

    @Test
    fun `precedence between run and check is not mutual`() = onEdt {
        val file = addModule("pkg/other.by", "x = 1\n", "pkg.other")
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val check = producer<ByCheckFromFileProducer>().createConfigurationFromContext(context)
        assertFalse(
            producer<ByCheckFromFileProducer>().shouldReplace(check!!, run!!),
            "if check also displaced run, the winner would be arbitrary",
        )
    }

    // ------------------------------------------------------------------
    // Cross-factory scanning
    //
    // findExistingConfiguration scans by configuration *type*, and `by run`/`by build`/`by check`
    // share one. Each producer must narrow to its own configuration class, or the generic bridge
    // casts a sibling and throws ClassCastException on rerun.
    // ------------------------------------------------------------------

    /** Saves a real `by run` configuration, the way a first context run would. */
    private fun saveByRunConfiguration(): RunnerAndConfigurationSettings {
        val type = BasedPythonRunConfigurationType.getInstance()
        val settings = RunManager.getInstance(project)
            .createConfiguration("by run pkg.saved", type.runFactory)
        (settings.configuration as ByRunConfiguration).options.module = "pkg.saved"
        RunManager.getInstance(project).addConfiguration(settings)
        return settings
    }

    @Test
    fun `check producer does not choke on a saved by run configuration`() = onEdt {
        saveByRunConfiguration()
        val file = fixture.addFileToProject("pkg/saved.by", "x = 1\n")
        // Before the fix this threw:
        //   ByRunConfiguration cannot be cast to ByCheckConfiguration
        producer<ByCheckFromFileProducer>().findExistingConfiguration(contextFor(file))
    }

    @Test
    fun `run producer does not choke on a saved by check configuration`() = onEdt {
        val type = BasedPythonRunConfigurationType.getInstance()
        val settings = RunManager.getInstance(project)
            .createConfiguration("by check pkg/saved.by", type.checkFactory)
        (settings.configuration as ByCheckConfiguration).options.paths = "pkg/saved.by"
        RunManager.getInstance(project).addConfiguration(settings)

        val file = fixture.addFileToProject("pkg/saved2.by", "x = 1\n")
        producer<ByRunFromFileProducer>().findExistingConfiguration(contextFor(file))
    }

    @Test
    fun `by run yields to the pytest producer so the chain holds`() = onEdt {
        val file = addTestFile("test_chain.by", "def test_addition():\n    assert 1 + 1 == 2\n")
        model.rememberModuleNames(mapOf(file.virtualFile to "test_chain"))
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val test = producer<ByTestFromFileProducer>().createConfigurationFromContext(context)
        assertNotNull(test, "the pytest producer should match a test declaration")
        assertNotNull(run, "by run also matches the file, which is why test must win")
        assertTrue(
            producer<ByTestFromFileProducer>().shouldReplace(test!!, run!!),
            "the pytest producer must replace by run on a test line",
        )
        assertFalse(
            producer<ByRunFromFileProducer>().shouldReplace(run, test),
            "by run must not replace the pytest producer",
        )
    }
}
