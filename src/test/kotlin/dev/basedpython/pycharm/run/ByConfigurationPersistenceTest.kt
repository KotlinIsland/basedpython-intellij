package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.tasks.ByTaskConfiguration
import dev.basedpython.pycharm.tasks.ByTaskConfigurationType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The environment a configuration was given survives what the platform does to it: writing it to
 * `workspace.xml` and reading it back, and cloning it (which every run, template copy and editor
 * apply goes through).
 *
 * The run configuration editors show an environment table and a "pass parent environment" box for
 * every one of these types; a value that is only held in memory is lost on restart and never even
 * reaches a run started from a clone.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByConfigurationPersistenceTest {

    private val fixture by codeInsightFixture()

    private val env = linkedMapOf("A" to "1", "B" to "two words")

    /** Each configuration type, freshly made, with a way to read and write its environment. */
    private fun configurations(): List<Configured> {
        val project = fixture.project
        val run = BasedPythonRunConfigurationType()
        val test = ByTestConfigurationType()
        val task = ByTaskConfigurationType()
        return listOf(
            common { ByRunConfiguration(project, run.runFactory, "run") },
            common { ByBuildConfiguration(project, run.buildFactory, "build") },
            common { ByCheckConfiguration(project, run.checkFactory, "check") },
            common { ByTestConfiguration(project, test.testFactory, "test") },
            Configured(
                make = { ByTaskConfiguration(project, task.taskFactory, "task") },
                envVars = { (it as ByTaskConfiguration).options.envVars },
                passParentEnv = { (it as ByTaskConfiguration).options.passParentEnv },
                set = { c, vars, pass ->
                    (c as ByTaskConfiguration).options.apply {
                        envVars = LinkedHashMap(vars)
                        passParentEnv = pass
                    }
                },
            ),
        )
    }

    private fun common(make: () -> RunConfiguration) = Configured(
        make = make,
        envVars = { options(it).envVars },
        passParentEnv = { options(it).passParentEnv },
        set = { c, vars, pass ->
            options(c).apply {
                envVars = LinkedHashMap(vars)
                passParentEnv = pass
            }
        },
    )

    private fun options(c: RunConfiguration): ByCommonOptions = when (c) {
        is ByRunConfiguration -> c.options
        is ByBuildConfiguration -> c.options
        is ByCheckConfiguration -> c.options
        is ByTestConfiguration -> c.options
        else -> error("not a by configuration: $c")
    }

    private class Configured(
        val make: () -> RunConfiguration,
        val envVars: (RunConfiguration) -> Map<String, String>,
        val passParentEnv: (RunConfiguration) -> Boolean,
        val set: (RunConfiguration, Map<String, String>, Boolean) -> Unit,
    )

    @Test
    fun `the environment survives being written and read back`() {
        for (c in configurations()) {
            val original = c.make().also { c.set(it, env, false) }
            val element = Element("configuration").also(original::writeExternal)

            val restored = c.make().also { it.readExternal(JDOMUtil.load(JDOMUtil.write(element))) }

            assertEquals(env, c.envVars(restored), "env vars of ${original.name}: ${JDOMUtil.write(element)}")
            assertFalse(c.passParentEnv(restored), "pass-parent-env of ${original.name}: ${JDOMUtil.write(element)}")
        }
    }

    @Test
    fun `the environment survives a clone and the clone does not share it`() {
        for (c in configurations()) {
            val original = c.make().also { c.set(it, env, false) }

            val clone = original.clone()

            assertEquals(env, c.envVars(clone), "env vars of a cloned ${original.name}")
            assertFalse(c.passParentEnv(clone), "pass-parent-env of a cloned ${original.name}")

            c.set(clone, mapOf("C" to "3"), true)
            assertEquals(env, c.envVars(original), "editing a clone of ${original.name} changed the original")
            assertFalse(c.passParentEnv(original))
        }
    }

    @Test
    fun `an untouched configuration writes no environment and inherits the parent's`() {
        for (c in configurations()) {
            val element = Element("configuration").also(c.make()::writeExternal)
            val xml = JDOMUtil.write(element)
            assertFalse(xml.contains("envVars"), "a default environment added an option line: $xml")
            assertFalse(xml.contains("passParentEnv"), "a default pass-parent-env added an option line: $xml")

            val restored = c.make().also { it.readExternal(JDOMUtil.load(xml)) }
            assertTrue(c.passParentEnv(restored))
            assertEquals(emptyMap<String, String>(), c.envVars(restored))
        }
    }
}
