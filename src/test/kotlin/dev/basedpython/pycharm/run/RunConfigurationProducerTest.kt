package dev.basedpython.pycharm.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.run.test.ByTestConfiguration

/**
 * Exercises the context-based run configuration producers end-to-end through real PSI files
 * created by the fixture. These are the code paths the gutter "run" icons drive via
 * [RunConfigurationProducer], so a failure here means the icons silently produce the wrong
 * (or no) configuration.
 */
class RunConfigurationProducerTest : BasePlatformTestCase() {

    /** Builds a [ConfigurationContext] anchored at the first leaf of [file]. */
    private fun contextFor(file: PsiFile): ConfigurationContext {
        val element = file.findElementAt(0) ?: file
        return ConfigurationContext(element)
    }

    private inline fun <reified T : RunConfigurationProducer<*>> producer(): T =
        RunConfigurationProducer.getInstance(T::class.java)

    // ------------------------------------------------------------------
    // by run
    // ------------------------------------------------------------------

    fun `test by run producer builds module name from by file`() {
        val file = myFixture.addFileToProject(
            "pkg/main.by",
            "if __name__ == \"__main__\":\n    main()\n",
        )
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull("by run producer should produce a configuration for a .by file", fromContext)
        val config = fromContext!!.configuration as ByRunConfiguration
        assertEquals("pkg.main", config.options.module)
        assertEquals("by run pkg.main", config.name)
    }

    fun `test by run producer ignores non-by files`() {
        val file = myFixture.configureByText("main.py", "print(1)\n")
        val fromContext = producer<ByRunFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNull("by run producer should not fire on .py files", fromContext)
    }

    // ------------------------------------------------------------------
    // by check
    // ------------------------------------------------------------------

    fun `test by check producer sets path from by file`() {
        val file = myFixture.addFileToProject("pkg/main.by", "x = 1\n")
        val fromContext = producer<ByCheckFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull("by check producer should produce a configuration for a .by file", fromContext)
        val config = fromContext!!.configuration as ByCheckConfiguration
        assertTrue(
            "check path should reference the file, was '${config.options.paths}'",
            config.options.paths.contains("main.by"),
        )
    }

    // ------------------------------------------------------------------
    // by test (gutter "Run test" icon must resolve to a ByTestConfiguration)
    // ------------------------------------------------------------------

    fun `test by test producer targets a top-level test function`() {
        val file = myFixture.addFileToProject(
            "test_thing.by",
            "def test_addition():\n    assert 1 + 1 == 2\n",
        )
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNotNull("by test producer should fire on a `def test_…` line", fromContext)
        val config = fromContext!!.configuration as ByTestConfiguration
        // The path prefix is project-relative; in the in-memory fixture it stays absolute, so
        // assert on the meaningful node-id suffix.
        assertTrue(
            "paths was '${config.options.paths}'",
            config.options.paths.endsWith("test_thing.by::test_addition"),
        )
        assertTrue("name was '${config.name}'", config.name.startsWith("by test "))
        assertTrue("name was '${config.name}'", config.name.endsWith("test_thing.by::test_addition"))
    }

    fun `test by test producer qualifies a method with its enclosing class`() {
        val file = myFixture.addFileToProject(
            "test_thing.by",
            "class TestMath:\n    def test_add(self):\n        assert True\n",
        )
        // Anchor the context on the `def test_add` line (second line).
        val offset = file.text.indexOf("def test_add")
        val element = file.findElementAt(offset) ?: file
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(ConfigurationContext(element))
        assertNotNull("by test producer should fire on a nested `def test_…` method", fromContext)
        val config = fromContext!!.configuration as ByTestConfiguration
        assertTrue(
            "paths was '${config.options.paths}'",
            config.options.paths.endsWith("test_thing.by::TestMath::test_add"),
        )
    }

    fun `test by test producer ignores non-test lines`() {
        val file = myFixture.addFileToProject("plain.by", "x = 1\n")
        val fromContext = producer<ByTestFromFileProducer>()
            .createConfigurationFromContext(contextFor(file))
        assertNull("by test producer should not fire on a non-test line", fromContext)
    }

    fun `test gutter context on a test line resolves to a by test config`() {
        val file = myFixture.addFileToProject(
            "test_thing.by",
            "def test_addition():\n    assert 1 + 1 == 2\n",
        )
        val context = contextFor(file)
        val produced = RunConfigurationProducer.getProducers(project)
            .mapNotNull { it.createConfigurationFromContext(context)?.configuration }
        val testConfig = produced.filterIsInstance<ByTestConfiguration>().firstOrNull()
        assertNotNull(
            "a .by test line should yield a `by test` configuration; produced=${produced.map { it::class.simpleName }}",
            testConfig,
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

    fun `test by run takes precedence over by check on a plain by file`() {
        val file = myFixture.addFileToProject("pkg/app.by", "x = 1\n")
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val check = producer<ByCheckFromFileProducer>().createConfigurationFromContext(context)
        assertNotNull("by run should match a plain .by file", run)
        assertNotNull("by check should also match it — that is the ambiguity", check)

        assertTrue(
            "by run must be preferred over by check, or a context run is ambiguous",
            producer<ByRunFromFileProducer>().isPreferredConfiguration(run, check),
        )
        assertTrue(
            "by run must replace by check, or the platform shows an unreadable chooser",
            producer<ByRunFromFileProducer>().shouldReplace(run!!, check!!),
        )
    }

    fun `test precedence between run and check is not mutual`() {
        val file = myFixture.addFileToProject("pkg/other.by", "x = 1\n")
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val check = producer<ByCheckFromFileProducer>().createConfigurationFromContext(context)
        assertFalse(
            "if check also displaced run, the winner would be arbitrary",
            producer<ByCheckFromFileProducer>().shouldReplace(check!!, run!!),
        )
    }

    fun `test by run yields to by test so the chain holds`() {
        val file = myFixture.addFileToProject(
            "test_chain.by",
            "def test_x():\n    assert True\n",
        )
        val context = contextFor(file)
        val run = producer<ByRunFromFileProducer>().createConfigurationFromContext(context)
        val test = producer<ByTestFromFileProducer>().createConfigurationFromContext(context)
        assertNotNull("by test should match a test declaration", test)
        assertNotNull("by run also matches the file, which is why test must win", run)
        assertTrue(
            "by test must replace by run on a test line",
            producer<ByTestFromFileProducer>().shouldReplace(test!!, run!!),
        )
        assertFalse(
            "by run must not replace by test",
            producer<ByRunFromFileProducer>().shouldReplace(run, test),
        )
    }
}
