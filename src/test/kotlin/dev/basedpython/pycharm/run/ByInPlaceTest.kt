package dev.basedpython.pycharm.run

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByCommandFlags
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a `by run` configuration runs its program in place, `by run --in-place`, and what that puts
 * on `by`'s command line.
 *
 * In place exactly when the project's `by` says, in its `initialize` answer, that its `by run` takes
 * `--in-place` (`by` a32cdb0b6: `{"run": {"inPlace": true, …}}`); a `by` that says nothing of it — the
 * bundled 0.0.1 among them — stages the program as it always has. On a run target the target's own
 * `by` says, once the target exists: `ByRunOnTargetTest`.
 */
@TestFixtures
class ByInPlaceTest {

    private val fixture by codeInsightFixture()

    private fun saying(experimental: String): ByCommandFlags = ByCommandFlags.of(
        InitializeResult(ServerCapabilities().apply { this.experimental = com.google.gson.JsonParser.parseString(experimental) }),
    )

    @Test
    fun `a by whose by run takes --in-place runs the program in place`() {
        val flags = saying("""{"run": {"inPlace": true, "inBuild": true}, "repl": {"inPlace": true}}""")
        assertTrue(ByInPlace.of(fixture.project, byFlags = "", onTarget = false) { flags }.value)
        assertTrue(flags.replsInPlace)
    }

    @Test
    fun `a by that does not say so stages it, as does one that answers nothing`() {
        assertFalse(ByInPlace.of(fixture.project, byFlags = "", onTarget = false) { saying("""{"run": {"inBuild": true}}""") }.value)
        assertFalse(ByInPlace.of(fixture.project, byFlags = "", onTarget = false) { ByCommandFlags.NONE }.value)
        assertFalse(saying("""{"run": {"inPlace": true}}""").replsInPlace, "repl's flag is its own")
    }

    /**
     * The target's `by` is not the one whose server answers here, so the server is not asked: the
     * target's `by` is, once the target exists, and says for the run.
     */
    @Test
    fun `a run on a target waits for the target's by, and the server is not asked`() {
        val never = { error("asked the server about a target's by") }
        val inPlace = ByInPlace.of(fixture.project, byFlags = "", onTarget = true, flags = never)
        assertTrue(inPlace.waitsForTarget)
        inPlace.decide(saying("""{"run": {"inPlace": true}}"""))
        assertFalse(inPlace.waitsForTarget)
        assertTrue(inPlace.value)
        inPlace.decide(ByCommandFlags.NONE)
        assertTrue(inPlace.value, "decided once, by the target's first answer")

        val staged = ByInPlace.of(fixture.project, byFlags = "", onTarget = true, flags = never)
        staged.decide(ByCommandFlags.NONE)
        assertFalse(staged.value, "a target's by that says nothing stages the program")
    }

    /** `by` refuses `--in-place` with `--in-build`, so a mode the user wrote is the one the run has. */
    @Test
    fun `a mode written among by's flags decides, and the server is not asked`() {
        val never = { error("asked the server about a mode the user wrote") }
        for (onTarget in listOf(false, true)) {
            val inPlace = ByInPlace.of(fixture.project, byFlags = "--soundness none --in-place", onTarget = onTarget, flags = never)
            assertTrue(inPlace.value && !inPlace.waitsForTarget)
            val staged = ByInPlace.of(fixture.project, byFlags = "--in-build", onTarget = onTarget, flags = never)
            assertFalse(staged.value || staged.waitsForTarget)
        }
    }

    @Test
    fun `--in-place goes with by's flags, before the module and the program's arguments`() = onEdt {
        val configuration = ByRunConfiguration(fixture.project, BasedPythonRunConfigurationType().runFactory, "run").apply {
            options.module = "app.main"
            options.programArgs = "--name x"
            options.extraArgs = "--soundness none"
        }
        val state = stateOf(configuration, inPlace = true)
        state.infrastructureArgs += listOf("--launcher", "/tmp/wrapper")
        assertEquals(
            listOf("run", "--in-place", "--launcher", "/tmp/wrapper", "--soundness", "none", "app.main", "--name", "x"),
            state.buildCommand(),
        )
    }

    @Test
    fun `a staged run carries no mode flag of its own`() = onEdt {
        val configuration = ByRunConfiguration(fixture.project, BasedPythonRunConfigurationType().runFactory, "run").apply {
            options.module = "app.main"
        }
        assertEquals(listOf("run", "app.main"), stateOf(configuration, inPlace = false).buildCommand())
    }

    /** The configuration's own `getState`, with the mode written where the server need not be asked. */
    @Test
    fun `a configuration whose flags say --in-place runs in place and says it once`() = onEdt {
        val configuration = ByRunConfiguration(fixture.project, BasedPythonRunConfigurationType().runFactory, "run").apply {
            options.module = "app.main"
            options.extraArgs = "--in-place"
        }
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val state = configuration.getState(executor, ExecutionEnvironmentBuilder.create(fixture.project, executor, configuration).build())
            as ByCommandLineState
        assertTrue(state.inPlace)
        assertEquals(listOf("run", "--in-place", "app.main"), state.buildCommand())
    }

    @Test
    fun `a configuration whose flags say --in-build is staged`() = onEdt {
        val configuration = ByRunConfiguration(fixture.project, BasedPythonRunConfigurationType().runFactory, "run").apply {
            options.module = "app.main"
            options.extraArgs = "--in-build"
        }
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val state = configuration.getState(executor, ExecutionEnvironmentBuilder.create(fixture.project, executor, configuration).build())
            as ByCommandLineState
        assertFalse(state.inPlace)
        assertEquals(listOf("run", "--in-build", "app.main"), state.buildCommand())
    }

    /** A `by run` state of [configuration]'s options, [inPlace] or not, as the configuration makes one. */
    private fun stateOf(configuration: ByRunConfiguration, inPlace: Boolean): ByCommandLineState {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(fixture.project, executor, configuration).build()
        return object : ByCommandLineState(fixture.project, configuration.options, environment) {
            override val subcommand = "run"
            override val inPlace: Boolean = inPlace
            override fun buildSubcommandArgs(): List<String> = runSubcommandArgs(configuration.options)
        }
    }
}
