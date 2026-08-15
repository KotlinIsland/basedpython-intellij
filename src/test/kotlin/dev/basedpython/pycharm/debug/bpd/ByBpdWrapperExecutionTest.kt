package dev.basedpython.pycharm.debug.bpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs the wrapper.
 *
 * Every other test of it reads the script as text, which proves it says what it was meant to say
 * and nothing about whether `sh` agrees. This one executes it exactly as `by run` does — as an
 * interpreter, twice, with `by run`'s two argument shapes — against a stand-in python and a
 * stand-in `bpd`.
 *
 * That covers the half of the handshake that lives in this repository. The stand-ins are the
 * boundary: what a real `bpd` prints is pinned by `ByBpdRecordTest` against `bpd`'s own
 * serialisation, and what a real `by run` passes is pinned by the two cases below.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the wrapper is a shell script; Windows is refused by name")
class ByBpdWrapperExecutionTest {

    /** The wrapper, written the way the plugin writes it and made runnable. */
    private fun wrapper(dir: Path): Path {
        val script = dir.resolve("bpd-python")
        Files.writeString(script, ByBpdWrapper.script())
        script.toFile().setExecutable(true)
        return script
    }

    /** A stand-in that reports what it was called with, so a pass-through can be seen. */
    private fun stub(dir: Path, name: String, body: String): Path {
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return script
    }

    private fun run(dir: Path, script: Path, record: Path, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf(script.toString()) + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()[ByBpdWrapper.ENV_PYTHON] = dir.resolve("python").toString()
                environment()[ByBpdWrapper.ENV_BPD] = dir.resolve("bpd").toString()
                environment()[ByBpdWrapper.ENV_PORT] = "51234"
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the wrapper did not exit")
        return process.exitValue() to output
    }

    @Test
    fun `a version probe reaches the real interpreter unchanged`(@TempDir dir: Path) {
        // the one call that must not be intercepted. `by run` asks which python it is emitting
        // code for, and a wrapper that answered it would make `by run` target the wrong version
        val script = wrapper(dir)
        stub(dir, "python", """echo "PYTHON $*"""")
        stub(dir, "bpd", """echo "BPD SHOULD NOT RUN"""")
        val record = dir.resolve("record")

        val (code, output) = run(dir, script, record, "-c", "import sys; print(sys.version_info)")

        assertEquals(0, code, output)
        assertTrue(output.startsWith("PYTHON -c"), "the probe did not reach the interpreter: $output")
        assertTrue(!output.contains("BPD"), "bpd was started for a version probe: $output")
        assertTrue(!Files.exists(record), "a probe wrote a record, and it is not the program")
    }

    @Test
    fun `the program is recorded and bpd is started, and the two share one file`(@TempDir dir: Path) {
        val script = wrapper(dir)
        stub(dir, "python", """echo "PYTHON SHOULD NOT RUN"""")
        // what `bpd dap --listen` really prints, in the shape `ByBpdRecord` reads
        stub(
            dir,
            "bpd",
            """echo '{"listening":{"host":"127.0.0.1","port":51234,"header":"x-bpd-token","token":"tok"}}'""",
        )
        val record = dir.resolve("record")

        val (code, output) = run(dir, script, record, "_by_runner.py", "demo", "--flag", "two words")

        assertEquals(0, code, output)
        val parsed = ByBpdRecord.parse(Files.readString(record))
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, parsed) { "record was:\n$parsed" }

        // an argument with a space is one argument, which is the whole reason the record is lines
        assertEquals(listOf("_by_runner.py", "demo", "--flag", "two words"), ready.argv)
        assertEquals(dir.toRealPath().toString(), Path.of(ready.cwd).toRealPath().toString())
        assertEquals(51234, ready.port)
        assertEquals("tok", ready.token)
        assertEquals("x-bpd-token", ready.tokenHeader)
    }

    @Test
    fun `bpd is given the port the IDE reserved`(@TempDir dir: Path) {
        // the IDE picks the port so it knows where to connect before anything starts. a wrapper
        // that dropped it would leave bpd on a port nobody is dialling
        val script = wrapper(dir)
        stub(dir, "python", "true")
        stub(dir, "bpd", """echo "ARGS $*"""")
        val record = dir.resolve("record")

        run(dir, script, record, "_by_runner.py", "demo")

        val written = Files.readString(record)
        assertTrue(written.contains("ARGS dap --listen 51234"), "bpd was started as: $written")
    }
}
