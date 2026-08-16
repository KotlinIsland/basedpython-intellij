package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The command line uv is actually given, for every operation the plugin can ask for.
 *
 * Checked against uv 0.12.3. A test per op rather than one table, because what each argument list
 * has to be is a separate fact and a table would report six failures for one wrong flag.
 */
class UvBackendTest {

    private fun args(op: EnvOp): List<String> = requireNotNull(UvBackend.command(op)).args

    @Test
    fun `create uses the project's own requires-python when no interpreter is named`() {
        assertEquals(listOf("venv"), args(EnvOp.Create()))
    }

    @Test
    fun `create on a named interpreter passes it through`() {
        assertEquals(listOf("venv", "--python", "3.12"), args(EnvOp.Create("3.12")))
    }

    @Test
    fun `sync is plain, and the drift probe is the same command asked not to act`() {
        assertEquals(listOf("sync"), args(EnvOp.Sync))
        assertEquals(listOf("sync", "--check"), args(EnvOp.CheckSync))
    }

    @Test
    fun `upgrade re-resolves rather than reinstalling`() {
        assertEquals(listOf("lock"), args(EnvOp.Lock))
        assertEquals(listOf("lock", "--upgrade"), args(EnvOp.Upgrade))
    }

    @Test
    fun `add and remove carry every requirement and the dev flag`() {
        assertEquals(listOf("add", "httpx", "rich>=13"), args(EnvOp.Add(listOf("httpx", "rich>=13"))))
        assertEquals(listOf("add", "--dev", "pytest"), args(EnvOp.Add(listOf("pytest"), dev = true)))
        assertEquals(listOf("remove", "httpx"), args(EnvOp.Remove(listOf("httpx"))))
        assertEquals(listOf("remove", "--dev", "pytest"), args(EnvOp.Remove(listOf("pytest"), dev = true)))
    }

    /**
     * The flag this exists for. Without it `uv pip list` reports whatever `VIRTUAL_ENV` names, which
     * in an IDE launched from an activated shell is a different project's environment.
     */
    @Test
    fun `listing packages names the interpreter it is asking about`() {
        val python = Path.of("/p/.venv/bin/python")
        assertEquals(
            listOf("pip", "list", "--format", "json", "--python", python.toString()),
            args(EnvOp.ListPackages(python)),
        )
    }

    @Test
    fun `listing packages with no interpreter falls back to uv's own discovery`() {
        assertEquals(listOf("pip", "list", "--format", "json"), args(EnvOp.ListPackages(null)))
    }

    /** No `--only-installed`: the picker offers versions to download as well as ones already here. */
    @Test
    fun `listing pythons includes download candidates`() {
        assertEquals(listOf("python", "list", "--output-format", "json"), args(EnvOp.ListPythons))
        assertEquals(listOf("python", "install", "3.13"), args(EnvOp.InstallPython("3.13")))
    }

    @Test
    fun `only the commands run for their output are queries`() {
        assertTrue(requireNotNull(UvBackend.command(EnvOp.CheckSync)).isQuery)
        assertTrue(requireNotNull(UvBackend.command(EnvOp.ListPackages(null))).isQuery)
        assertTrue(requireNotNull(UvBackend.command(EnvOp.ListPythons)).isQuery)
        assertFalse(requireNotNull(UvBackend.command(EnvOp.Sync)).isQuery)
        assertFalse(requireNotNull(UvBackend.command(EnvOp.Add(listOf("httpx")))).isQuery)
    }

    /** 0 and 1 are the two answers `uv sync --check` gives; anything else is uv failing. */
    @Test
    fun `drift comes from the exit code, and an unexpected one settles nothing`() {
        assertEquals(EnvDrift.IN_SYNC, UvBackend.driftFromExitCode(0))
        assertEquals(EnvDrift.OUT_OF_SYNC, UvBackend.driftFromExitCode(1))
        assertEquals(EnvDrift.UNKNOWN, UvBackend.driftFromExitCode(2))
        assertEquals(EnvDrift.UNKNOWN, UvBackend.driftFromExitCode(-1))
    }

    @Test
    fun `a project is claimed by either marker`(@TempDir dir: Path) {
        assertFalse(UvBackend.claims(dir))
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = 'x'\n")
        assertTrue(UvBackend.claims(dir))
    }

    @Test
    fun `the environment is dot-venv at the project root`(@TempDir dir: Path) {
        assertEquals(dir.resolve(".venv"), UvBackend.environmentRoot(dir))
    }
}
