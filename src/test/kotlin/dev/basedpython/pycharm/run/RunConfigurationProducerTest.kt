package dev.basedpython.pycharm.run

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.run.main.ByMainArgumentHistory
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Exercises the context-based run configuration producers end-to-end through real PSI files
 * created by the fixture. These are the code paths the gutter "run" icons drive via
 * [RunConfigurationProducer], so a failure here means the icons silently produce the wrong
 * (or no) configuration.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class RunConfigurationProducerTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    /** Builds a [ConfigurationContext] anchored at the first leaf of [file]. */
    private fun contextFor(file: PsiFile): ConfigurationContext {
        val element = file.findElementAt(0) ?: file
        return ConfigurationContext(element)
    }

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
    private fun asBasedPythonProject(body: () -> Unit) {
        val settings = BasedPythonSettings.getInstance(project)
        val handling = settings.pyFileHandling
        val enabled = settings.byEnabled
        val marker = Paths.get(project.basePath!!).also { Files.createDirectories(it) }.resolve("api.lock")
        val created = !Files.exists(marker)
        if (created) Files.createFile(marker)
        settings.byEnabled = true
        settings.pyFileHandling = PyFileHandling.ALWAYS
        try {
            body()
        } finally {
            settings.pyFileHandling = handling
            settings.byEnabled = enabled
            if (created) Files.deleteIfExists(marker)
        }
    }

    // ------------------------------------------------------------------
    // by run
    // ------------------------------------------------------------------

    @Test
    fun `by run producer builds module name from by file`() {
        val file = fixture.addFileToProject(
            "pkg/main.by",
            "if __name__ == \"__main__\":\n    main()\n",
        )
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by run producer should produce a configuration for a .by file")
        val config = fromContext!!.configuration as ByRunConfiguration
        assertEquals("pkg.main", config.options.module)
        assertEquals("pkg.main", config.name)
    }

    @Test
    fun `a context configuration is seeded with what the module was last run with`() {
        // This is what keeps the argument prompt to once per program: the gutter's plain Run picks
        // up the arguments the form was last given, instead of starting bare and failing again.
        ByMainArgumentHistory.remember(project, "seeded.main", "--name bob")
        val file = fixture.addFileToProject("seeded/main.by", "def main(name: str):\n    print(name)\n")
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
    fun `by run producer ignores a py file it does not own`() {
        val file = fixture.configureByText("main.py", "print(1)\n")
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
        val file = fixture.addFileToProject("pkg/script.py", "print(1)\n")
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "by run producer should produce a configuration for an owned .py")
        assertEquals("pkg.script", (fromContext!!.configuration as ByRunConfiguration).options.module)
    }

    /**
     * `twin.by` and `twin.py` are one module name for two files, and `by run` resolves it to the
     * transpiled one — its temp directory is `sys.path[0]`. A configuration produced from the `.py`
     * would run the `.by` instead, so the `.py` declines and the `.by` keeps the name.
     */
    @Test
    fun `a py shadowed by a by of the same name produces nothing`() = asBasedPythonProject {
        fixture.addFileToProject("twin.by", "print(1)\n")
        val shadowed = fixture.addFileToProject("twin.py", "print(2)\n")
        assertNull(
            producer<ByRunFromFileProducer>().createConfigurationFromContext(contextFor(shadowed)),
            "the shadowed .py should not offer to run the .by beside it",
        )
        assertNotNull(
            producer<ByRunFromFileProducer>()
                .createConfigurationFromContext(contextFor(fixture.addFileToProject("other.by", "x = 1\n"))),
            "the .by itself is unaffected",
        )
    }

    // ------------------------------------------------------------------
    // by check
    // ------------------------------------------------------------------

    @Test
    fun `by check producer sets path from by file`() {
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
    fun `the pytest producer targets a top-level test function`() {
        val file = fixture.addFileToProject(
            "test_thing.by",
            "def test_addition():\n    assert 1 + 1 == 2\n",
        )
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull(fromContext, "the pytest producer should fire on a `def test_…` line")
        val config = fromContext!!.configuration as ByTestConfiguration
        // The path prefix is project-relative; in the in-memory fixture it stays absolute, so
        // assert on the meaningful node-id suffix.
        assertTrue(
            config.options.paths.endsWith("test_thing.by::test_addition"),
            "paths was '${config.options.paths}'",
        )
        assertTrue(config.name.startsWith("pytest "), "name was '${config.name}'")
        assertTrue(config.name.endsWith("test_thing.by::test_addition"), "name was '${config.name}'")
    }

    @Test
    fun `the pytest producer qualifies a method with its enclosing class`() {
        val file = fixture.addFileToProject(
            "test_thing.by",
            "class TestMath:\n    def test_add(self):\n        assert True\n",
        )
        // Anchor the context on the `def test_add` line (second line).
        val offset = file.text.indexOf("def test_add")
        val element = file.findElementAt(offset) ?: file
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(ConfigurationContext(element))
        assertNotNull(fromContext, "the pytest producer should fire on a nested `def test_…` method")
        val config = fromContext!!.configuration as ByTestConfiguration
        assertTrue(
            config.options.paths.endsWith("test_thing.by::TestMath::test_add"),
            "paths was '${config.options.paths}'",
        )
    }

    @Test
    fun `the pytest producer ignores non-test lines`() {
        val file = fixture.addFileToProject("plain.by", "x = 1\n")
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNull(fromContext, "the pytest producer should not fire on a non-test line")
    }

    @Test
    fun `gutter context on a test line resolves to a test config`() {
        val file = fixture.addFileToProject(
            "test_thing.by",
            "def test_addition():\n    assert 1 + 1 == 2\n",
        )
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
    fun `by run takes precedence over by check on a plain by file`() {
        val file = fixture.addFileToProject("pkg/app.by", "x = 1\n")
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
    fun `precedence between run and check is not mutual`() {
        val file = fixture.addFileToProject("pkg/other.by", "x = 1\n")
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
    fun `check producer does not choke on a saved by run configuration`() {
        saveByRunConfiguration()
        val file = fixture.addFileToProject("pkg/saved.by", "x = 1\n")
        // Before the fix this threw:
        //   ByRunConfiguration cannot be cast to ByCheckConfiguration
        producer<ByCheckFromFileProducer>().findExistingConfiguration(contextFor(file))
    }

    @Test
    fun `run producer does not choke on a saved by check configuration`() {
        val type = BasedPythonRunConfigurationType.getInstance()
        val settings = RunManager.getInstance(project)
            .createConfiguration("by check pkg/saved.by", type.checkFactory)
        (settings.configuration as ByCheckConfiguration).options.paths = "pkg/saved.by"
        RunManager.getInstance(project).addConfiguration(settings)

        val file = fixture.addFileToProject("pkg/saved2.by", "x = 1\n")
        producer<ByRunFromFileProducer>().findExistingConfiguration(contextFor(file))
    }

    @Test
    fun `by run yields to the pytest producer so the chain holds`() {
        val file = fixture.addFileToProject(
            "test_chain.by",
            "def test_x():\n    assert True\n",
        )
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
