package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The single question the environment window leads with: what, if anything, needs doing.
 *
 * Worth its own test because the order of the checks is the feature. A project with no uv *and* no
 * environment has to be told about uv first — telling it to create an environment with a tool it
 * does not have is a button that cannot work.
 */
class EnvStatusTest {

    private val root: Path = Path.of("/p")

    private fun status(
        backend: EnvBackend? = UvBackend,
        tool: Path? = Path.of("/usr/bin/uv"),
        environment: ManagedEnvironment? = ManagedEnvironment("uv", root.resolve(".venv"), root.resolve(".venv/bin/python"), "3.12"),
        drift: EnvDrift = EnvDrift.IN_SYNC,
    ) = EnvStatus(
        projectRoot = root,
        backend = backend,
        toolPath = tool,
        environmentRoot = root.resolve(".venv"),
        environment = environment,
        drift = drift,
        packages = emptyList(),
    )

    @Test
    fun `a project no backend claims is unmanaged`() {
        assertEquals(EnvHealth.UNMANAGED, status(backend = null).health)
    }

    /** Before uv is here, nothing else about the project can be acted on. */
    @Test
    fun `a missing tool outranks a missing environment`() {
        assertEquals(
            EnvHealth.TOOL_MISSING,
            status(tool = null, environment = null, drift = EnvDrift.OUT_OF_SYNC).health,
        )
    }

    @Test
    fun `a missing environment outranks drift`() {
        assertEquals(
            EnvHealth.NO_ENVIRONMENT,
            status(environment = null, drift = EnvDrift.OUT_OF_SYNC).health,
        )
    }

    @Test
    fun `an environment that does not match what the project declares is out of sync`() {
        assertEquals(EnvHealth.OUT_OF_SYNC, status(drift = EnvDrift.OUT_OF_SYNC).health)
    }

    @Test
    fun `everything in place is ready`() {
        assertEquals(EnvHealth.READY, status().health)
    }

    /**
     * A drift probe that could not be run says nothing, and must not be presented as a problem.
     * Reporting UNKNOWN as OUT_OF_SYNC would put a "sync me" banner on every project whose uv failed
     * for an unrelated reason.
     */
    @Test
    fun `drift that was never established is not a problem to report`() {
        assertEquals(EnvHealth.READY, status(drift = EnvDrift.UNKNOWN).health)
    }

    @Test
    fun `only the states with a button are actionable`() {
        assertFalse(EnvHealth.UNMANAGED.isActionable)
        assertFalse(EnvHealth.READY.isActionable)
        assertTrue(EnvHealth.TOOL_MISSING.isActionable)
        assertTrue(EnvHealth.NO_ENVIRONMENT.isActionable)
        assertTrue(EnvHealth.OUT_OF_SYNC.isActionable)
    }

    /** "Not looked yet" must not read as "this project has no environment manager". */
    @Test
    fun `the initial status keeps the project root and claims nothing else`() {
        val unknown = EnvStatus.unknown(root)
        assertEquals(root, unknown.projectRoot)
        assertEquals(EnvHealth.UNMANAGED, unknown.health)
        assertEquals(EnvDrift.UNKNOWN, unknown.drift)
    }
}
