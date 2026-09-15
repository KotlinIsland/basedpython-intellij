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
 * The whole bpd backend rests on one contract with a program this repository does not own: that
 * `by run --launcher <wrapper>` starts `<wrapper> <python> <runner> <module> <args...>` exactly
 * once, with the interpreter its own discovery chose. [ByBpdWrapperExecutionTest] checks the
 * wrapper handles that shape — against a shape *this* repository wrote down. This one gets it from
 * `by`.
 *
 * If `by run` ever stops handing the launcher the interpreter, or runs it for the version probe
 * too, or stops passing the module after the runner, that is a silently broken debugger. It fails
 * here instead.
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

    /**
     * An interpreter that notes every call and then behaves like the real one, so a version probe
     * is seen reaching it rather than the wrapper.
     */
    private fun interpreter(dir: Path, log: Path): Path {
        val script = dir.resolve("env/bin/python3")
        Files.createDirectories(script.parent)
        Files.writeString(
            script,
            """
            #!/bin/sh
            echo "CALLED ${'$'}*" >> "$log"
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
        val script = dir.resolve("toolchain/bpd")
        Files.createDirectories(script.parent)
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
    fun `by run discovers the interpreter itself and hands it and the program to the wrapper`(@TempDir dir: Path) {
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

        val calls = dir.resolve("calls")
        val python = interpreter(dir, calls)
        val record = dir.resolve("record")
        val saw = dir.resolve("bpd-saw")
        // no `--python`: the interpreter is `by run`'s to choose. A project with no environment of
        // its own takes `PYTHON`, which is how this test names one without the IDE naming it
        val process = ProcessBuilder(by.toString(), "run", "--launcher", wrapper.toString(), "demo")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment().remove("VIRTUAL_ENV")
                environment()["PYTHON"] = python.toString()
                environment()[ByBpdWrapper.ENV_BPD] = announcer(dir, saw).toString()
                environment()[ByBpdWrapper.ENV_BPD_FALLBACK] = ""
                environment()[ByBpdWrapper.ENV_PORT] = "51234"
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "`by run` did not finish:\n$output")

        // 1. the version probe went to the interpreter, not through the wrapper: the wrapper is run
        //    once, for the program, and never has to tell the two apart
        val called = if (Files.exists(calls)) Files.readString(calls) else ""
        assertTrue(
            called.contains("CALLED -c"),
            "`by run` did not probe the interpreter it chose. it asked:\n$called\nand said:\n$output",
        )

        // 2. the program reached bpd, with the interpreter, the working directory and the
        //    arguments `by run` decided on — none of which the IDE can know in advance
        assertTrue(Files.exists(record), "no record was written. `by run` said:\n$output")
        val ready = assertInstanceOf(
            ByBpdRecord.Ready::class.java,
            ByBpdRecord.parse(Files.readString(record)),
        ) { "the record was:\n${Files.readString(record)}\n`by run` said:\n$output" }

        assertEquals(
            python.toString(),
            ready.python,
            "the wrapper was not handed the interpreter `by run` discovered",
        )
        assertEquals(
            "_by_runner.py",
            ready.argv.firstOrNull()?.let { Path.of(it).fileName.toString() },
            "`by run` no longer starts the program through the runner shim: ${ready.argv}",
        )
        assertTrue(
            ready.argv.contains("demo"),
            "the module is what `by run` forwards after the shim: ${ready.argv}",
        )

        // 3. bpd was started *in* the transpiled tree, not in the project. The source map lives in
        //    that tree and the launch request names the program relative to it, so a bpd started
        //    anywhere else launches nothing at all
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
