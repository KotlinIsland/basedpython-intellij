package dev.basedpython.pycharm.debug.bpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
 * `by run` really starting the wrapper.
 *
 * The whole bpd backend rests on one assumption about a program this repository does not own:
 * that `by run` calls `$PYTHON` exactly twice, once to ask which version to emit code for and once
 * to run the program. [ByBpdWrapperExecutionTest] checks the wrapper handles those two shapes —
 * against shapes *this* repository wrote down. This one gets them from `by`.
 *
 * If `by run` ever calls `$PYTHON` a third way, or changes the probe, or stops passing the module
 * after the runner, that is a silently broken debugger. It fails here instead.
 *
 * **Skipped unless `BASEDPYTHON_BY_UNDER_TEST` names a `by` binary**, because a plugin's test
 * suite cannot require a Rust toolchain's output — and because putting one on `PATH` would break
 * eight other tests here, which assert on what the plugin does when `by` is absent.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the wrapper is a shell script; Windows is refused by name")
class ByRunDrivesTheWrapperTest {

    private companion object {
        /** Points this test at a `by` without putting one where the rest of the suite sees it. */
        const val BY_UNDER_TEST = "BASEDPYTHON_BY_UNDER_TEST"
    }

    /**
     * The `by` to drive, named by an environment variable rather than found on `PATH`.
     *
     * **Deliberately not `PATH`.** Several tests in this suite assert on what the plugin does when
     * `by` is *absent* — `ByTypeInfoProviderTest` expects "the by language server is not running",
     * and the LSP highlighting tests expect no server to answer. A `by` on `PATH` makes the plugin
     * start a real language server underneath them, and eight of them fail. So this one test opts
     * in by its own variable, and the rest of the suite sees the machine it always saw.
     */
    private fun by(): Path? = System.getenv(BY_UNDER_TEST)
        ?.let { Path.of(it) }
        ?.takeIf { Files.isExecutable(it) }

    /** A stand-in that records what it was asked and then behaves like a real interpreter. */
    private fun passthrough(dir: Path, log: Path): Path {
        val script = dir.resolve("python")
        Files.writeString(
            script,
            """
            #!/bin/sh
            echo "PROBE ${'$'}*" >> "$log"
            exec python3 "${'$'}@"
            """.trimIndent() + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    /**
     * A stand-in `bpd dap --listen` that announces and exits, so `by run` finishes.
     *
     * It first writes down what it can see from where it was started, into [saw]. That has to
     * happen here rather than in the assertions: `by run` deletes the transpiled tree as soon as
     * the program ends, and the program is this script — so by the time the test looks, the
     * directory the real bpd would be sitting in no longer exists.
     */
    private fun announcer(dir: Path, saw: Path): Path {
        val script = dir.resolve("bpd")
        Files.writeString(
            script,
            "#!/bin/sh\n" +
                """printf 'pwd %s\n' "${'$'}PWD" > "$saw"""" + "\n" +
                """if [ -f _by_runner.py ]; then echo 'runner yes' >> "$saw";""" +
                """ else echo 'runner no' >> "$saw"; fi""" + "\n" +
                """echo '{"listening":{"host":"127.0.0.1","port":51234,""" +
                """"header":"x-bpd-token","token":"tok"}}'""" + "\n",
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test
    fun `by run probes the interpreter and then hands the program to the wrapper`(@TempDir dir: Path) {
        val by = by()
        assumeTrue(
            by != null,
            "set $BY_UNDER_TEST to a `by` binary to run this; there is nothing to drive otherwise",
        )

        Files.writeString(
            dir.resolve("demo.by"),
            "def main():\n    limit = 5\n    print(limit)\n",
        )
        val wrapper = dir.resolve("bpd-python")
        Files.writeString(wrapper, ByBpdWrapper.script())
        wrapper.toFile().setExecutable(true)

        val probes = dir.resolve("probes")
        val record = dir.resolve("record")
        val saw = dir.resolve("bpd-saw")
        // `--python`, not the `PYTHON` variable: `by run` resolves the project's own environment
        // first and reads the variable only below that, so in any project with a `.venv` the
        // variable is never consulted. The variable is set as well, exactly as the plugin sets
        // both, so this also pins that the two together never fight.
        val process = ProcessBuilder(by.toString(), "run", "--python", wrapper.toString(), "demo")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["PYTHON"] = wrapper.toString()
                environment()[ByBpdWrapper.ENV_PYTHON] = passthrough(dir, probes).toString()
                environment()[ByBpdWrapper.ENV_BPD] = announcer(dir, saw).toString()
                environment()[ByBpdWrapper.ENV_PORT] = "51234"
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "`by run` did not finish:\n$output")

        // 1. the probe reached the real interpreter. a wrapper that swallowed it would make
        //    `by run` emit code for a python that is not the one running it
        val probed = if (Files.exists(probes)) Files.readString(probes) else ""
        assertTrue(
            probed.contains("PROBE -c"),
            "`by run` no longer probes with `-c`, which is the shape the wrapper passes through. " +
                "it asked:\n$probed\nand said:\n$output",
        )

        // 2. the program reached bpd, with the working directory `by run` transpiled into and the
        //    arguments it decided on — neither of which the IDE can know in advance
        assertTrue(Files.exists(record), "no record was written. `by run` said:\n$output")
        val ready = assertInstanceOf(
            ByBpdRecord.Ready::class.java,
            ByBpdRecord.parse(Files.readString(record)),
        ) { "the record was:\n${Files.readString(record)}\n`by run` said:\n$output" }

        // by name rather than by whole path: `by run` used to name the shim relative to the tree
        // it had chdir'd into and now names it absolutely, and either is fine — the wrapper stands
        // where the shim is, so the launch request's relative `_by_runner.py` resolves either way
        assertEquals(
            "_by_runner.py",
            ready.argv.firstOrNull()?.let { Path.of(it).fileName.toString() },
            "`by run` no longer starts the program through the runner shim: ${ready.argv}",
        )
        assertTrue(
            ready.argv.contains("demo"),
            "the module is what `by run` forwards after the shim: ${ready.argv}",
        )

        // 3. the heart of it: bpd was started *in* the transpiled tree, not in the project. The
        //    source map lives in that tree and the launch request names the program relative to
        //    it, so a bpd started anywhere else launches nothing at all. `by run` used to put the
        //    program there itself and now runs from the project root, so the wrapper is what
        //    guarantees this — which is exactly why it is worth a test that drives the real `by`
        val seen = if (Files.exists(saw)) Files.readString(saw) else ""
        assertTrue(
            seen.contains("runner yes"),
            "bpd could not see `_by_runner.py` from where it was started, so the launch " +
                "request's relative program name resolves to nothing. it saw:\n$seen",
        )
        // and the directory the wrapper recorded is the one bpd really stood in — the record is
        // what the IDE reads, so a record naming somewhere else would be a lie it acts on
        assertTrue(
            seen.contains("pwd ${ready.cwd}\n"),
            "the record says bpd was started in ${ready.cwd}, but it saw:\n$seen",
        )
        assertTrue(
            Path.of(ready.cwd) != dir,
            "bpd was started in the project directory rather than the tree `by run` transpiled " +
                "into: ${ready.cwd}",
        )
    }
}
