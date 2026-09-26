package dev.basedpython.pycharm.run.ergonomics

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByCheckConfiguration
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.tasks.ByTaskConfiguration
import dev.basedpython.pycharm.tasks.ByTaskConfigurationType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The build a run configuration asks for first is the build *that configuration* would get: the
 * same `by`, the same directory, the same environment. It used to be a bare `by build` at the
 * project base whatever the configuration said.
 */
@TestFixtures
class BuildBeforeRunTaskProviderTest {

    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var dir: Path

    private lateinit var by: Path

    @BeforeEach
    fun fakeBy() = onEdt {
        by = Files.createFile(dir.resolve("by"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")))
        BasedPythonSettings.getInstance(fixture.project).byPath = by.toString()
    }

    @AfterEach
    fun forgetBy() = onEdt {
        BasedPythonSettings.getInstance(fixture.project).byPath = null
    }

    private val runType = BasedPythonRunConfigurationType()

    private fun ByCommonOptions.configure() {
        workingDir = dir.toString()
        pythonVersion = "3.12"
        envVars = linkedMapOf("A" to "1")
        passParentEnv = false
    }

    @Test
    fun `a run configuration's build uses its directory, environment and version`() = onEdt {
        val run = ByRunConfiguration(fixture.project, runType.runFactory, "run").apply {
            options.configure()
            options.extraArgs = "--compiled"
        }

        val cmd = buildCommandLine(run)

        assertEquals(by.toString(), cmd.exePath)
        // The run's extra args are `by run`'s flags; `by build` would reject `--compiled`.
        assertEquals(listOf("build", "--min-version", "3.12"), cmd.parametersList.list)
        assertEquals(dir.toString(), cmd.workDirectory?.path)
        assertEquals("1", cmd.environment["A"])
        assertEquals(GeneralCommandLine.ParentEnvironmentType.NONE, cmd.parentEnvironmentType)
    }

    @Test
    fun `a test configuration is a by configuration too`() = onEdt {
        val test = ByTestConfiguration(fixture.project, ByTestConfigurationType().testFactory, "test").apply {
            options.configure()
        }

        val cmd = buildCommandLine(test)

        assertEquals(dir.toString(), cmd.workDirectory?.path)
        assertEquals("1", cmd.environment["A"])
    }

    @Test
    fun `a check configuration's version is not a minimum version`() = onEdt {
        val check = ByCheckConfiguration(fixture.project, runType.checkFactory, "check").apply {
            options.configure()
        }

        assertEquals(listOf("build"), buildCommandLine(check).parametersList.list)
    }

    @Test
    fun `the configuration's environment choice decides which by builds`() = onEdt {
        // The configured path applies to AUTO only; a run pinned to a venv the project does not
        // have cannot run, and neither can the build before it.
        val run = ByRunConfiguration(fixture.project, runType.runFactory, "run").apply {
            options.environmentKind = ByEnvironmentKind.VENV
        }

        assertThrows<ExecutionException> { buildCommandLine(run) }
        assertFalse(BuildBeforeRunTaskProvider().canExecuteTask(run, BuildBeforeRunTask()))
    }

    @Test
    fun `any other configuration builds with the project's defaults`() = onEdt {
        val task = ByTaskConfiguration(fixture.project, ByTaskConfigurationType().taskFactory, "task")

        val cmd = buildCommandLine(task)

        assertEquals(listOf("build"), cmd.parametersList.list)
        assertEquals(GeneralCommandLine.ParentEnvironmentType.CONSOLE, cmd.parentEnvironmentType)
    }
}
