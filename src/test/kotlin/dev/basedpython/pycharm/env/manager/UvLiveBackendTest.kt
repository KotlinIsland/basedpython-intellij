package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The commands [UvBackend] produces, run against a real uv, on a real project.
 *
 * Every other test in this package checks one half of a join: [UvBackendTest] says what the argv is,
 * [UvOutputTest] says what the output parses to. Neither notices when the argv stops producing that
 * output — a renamed flag, a `--format` that becomes `--output-format`, an exit code that changes
 * meaning. This creates a project, syncs it, breaks it, and checks that each command still says what
 * the plugin believes it says.
 *
 * **Skipped unless `BASEDPYTHON_UV_UNDER_TEST` names a uv binary.** An environment variable rather
 * than `PATH`, following [dev.basedpython.pycharm.debug.bpd.ByBpdLiveSessionTest]: a test suite that
 * silently changes behaviour depending on what is installed on the machine is worse than one that
 * skips.
 *
 * Runs uv offline where it can. A cold `uv sync` on a machine with no cache needs the network, and
 * the project below is deliberately dependency-free so that the sync has nothing to fetch.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the assertions read POSIX venv layout (bin/, not Scripts/)")
class UvLiveBackendTest {

    private companion object {
        const val UV = "BASEDPYTHON_UV_UNDER_TEST"

        /** Long enough for a resolve; short enough that a hung uv fails the build rather than the timeout. */
        const val TIMEOUT_SECONDS = 120L
    }

    private fun uv(): Path? = System.getenv(UV)
        ?.let { Path.of(it) }
        ?.takeIf { Files.isExecutable(it) }

    /** Runs one of the backend's own commands and returns (exit code, stdout). */
    private fun run(uv: Path, dir: Path, op: EnvOp): Pair<Int, String> {
        val command = requireNotNull(UvBackend.command(op)) { "uv cannot express $op" }
        val process = ProcessBuilder(listOf(uv.toString()) + command.args)
            .directory(dir.toFile())
            // uv reads the user's own config and cache otherwise, which makes the result depend on
            // the developer's machine. A cache of its own keeps this hermetic apart from the network.
            .apply {
                environment()["UV_CACHE_DIR"] = dir.resolve(".uv-cache").toString()
                environment()["UV_NO_CONFIG"] = "1"
                environment().remove("VIRTUAL_ENV")
            }
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue(
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "${command.describe("uv")} did not finish in ${TIMEOUT_SECONDS}s",
        )
        // Printed rather than asserted on: a failure here is nearly always uv complaining about
        // something the test set up, and the complaint is the useful part of the report.
        if (process.exitValue() != 0) println("${command.describe("uv")} -> ${process.exitValue()}: $stderr")
        return process.exitValue() to stdout
    }

    @Test
    fun `the commands this plugin sends drive a real uv through a whole project lifecycle`(@TempDir dir: Path) {
        val uv = uv()
        assumeTrue(uv != null, "set $UV to a uv binary to run this")
        requireNotNull(uv)

        // A project with no dependencies, so the sync below needs no network.
        Files.writeString(
            dir.resolve("pyproject.toml"),
            """
            [project]
            name = "live-test"
            version = "0.1.0"
            requires-python = ">=3.9"
            dependencies = []
            """.trimIndent(),
        )

        // --- the backend recognises it, and knows where the environment goes ---
        assertTrue(UvBackend.claims(dir), "a pyproject.toml is a uv project")
        val envRoot = UvBackend.environmentRoot(dir)
        assertEquals(dir.resolve(".venv"), envRoot)

        // --- create ---
        val (createExit, _) = run(uv, dir, EnvOp.Create())
        assertEquals(0, createExit, "uv venv")
        val python = UvBackend.pythonExecutable(envRoot)
        assertTrue(Files.isExecutable(python), "the interpreter is where pythonExecutable says: $python")

        // --- the environment's own record of itself parses ---
        val cfg = PyvenvCfg.parse(Files.readString(envRoot.resolve("pyvenv.cfg")))
        assertNotNull(cfg.version, "pyvenv.cfg carries a version this parser can find")
        assertNotNull(cfg.featureVersion)
        assertTrue(cfg.createdBy?.startsWith("uv") == true, "uv stamps itself: ${cfg.createdBy}")

        // --- sync, then the drift probe agrees ---
        assertEquals(0, run(uv, dir, EnvOp.Sync).first, "uv sync")
        assertEquals(
            EnvDrift.IN_SYNC,
            UvBackend.driftFromExitCode(run(uv, dir, EnvOp.CheckSync).first),
            "a freshly synced environment is in sync",
        )

        // --- add: the package appears in the list, aimed at this interpreter ---
        // A pure-Python package with no dependencies of its own, so this needs one small download.
        assertEquals(0, run(uv, dir, EnvOp.Add(listOf("iniconfig"))).first, "uv add iniconfig")
        val (listExit, listOut) = run(uv, dir, EnvOp.ListPackages(python))
        assertEquals(0, listExit, "uv pip list")
        val packages = UvBackend.parsePackages(listOut)
        assertTrue(
            packages.any { it.name.equals("iniconfig", ignoreCase = true) },
            "the added package is in the parsed list: ${packages.map { it.name }}",
        )

        // --- the drift probe reports a real drift ---
        // Uninstalling behind uv's back is exactly the state the banner exists to catch.
        assertEquals(0, run(uv, dir, EnvOp.Remove(listOf("iniconfig"))).first, "uv remove iniconfig")
        Files.writeString(
            dir.resolve("pyproject.toml"),
            Files.readString(dir.resolve("pyproject.toml")).replace("dependencies = []", "dependencies = [\"iniconfig\"]"),
        )
        assertEquals(
            EnvDrift.OUT_OF_SYNC,
            UvBackend.driftFromExitCode(run(uv, dir, EnvOp.CheckSync).first),
            "a declared dependency that is not installed is drift",
        )

        // --- interpreters parse, and at least the one running this project is reported installed ---
        val (pythonsExit, pythonsOut) = run(uv, dir, EnvOp.ListPythons)
        assertEquals(0, pythonsExit, "uv python list")
        val candidates = UvBackend.parsePythons(pythonsOut)
        assertTrue(candidates.isNotEmpty(), "uv knows about some interpreters")
        assertTrue(candidates.any { it.isInstalled }, "at least one is installed — it just built a venv")
        assertTrue(
            candidates.all { it.featureVersion.count { c -> c == '.' } == 1 },
            "every feature version is major.minor",
        )
    }
}
