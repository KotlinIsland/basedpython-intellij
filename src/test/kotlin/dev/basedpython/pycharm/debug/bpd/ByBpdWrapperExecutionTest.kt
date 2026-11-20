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
 * and nothing about whether `sh` agrees. This one executes it exactly as `by run --launcher` does —
 * the interpreter `by run` chose, then the runner and the program — against a stand-in `bpd`.
 *
 * That covers the half of the handshake that lives in this repository. The stand-ins are the
 * boundary: what a real `bpd` prints is pinned by `ByBpdRecordTest` against `bpd`'s own
 * serialisation, and what a real `by run` passes is pinned by [ByRunDrivesTheWrapperTest].
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the wrapper is a shell script; Windows is refused by name")
class ByBpdWrapperExecutionTest {

    private val announcement =
        """{"listening":{"host":"127.0.0.1","port":51234,"header":"x-bpd-token","token":"tok"}}"""

    /** The wrapper, written the way the plugin writes it and made runnable. */
    private fun wrapper(dir: Path): Path {
        val script = dir.resolve("bpd-python")
        Files.writeString(script, ByBpdWrapper.script())
        script.toFile().setExecutable(true)
        return script
    }

    /** A stand-in that says who it is and announces, so the record stays readable. */
    private fun stub(path: Path, body: String = "echo '$announcement'"): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n$body\n")
        path.toFile().setExecutable(true)
        return path
    }

    private fun run(
        dir: Path,
        script: Path,
        record: Path,
        args: List<String>,
        besideBy: Path? = null,
        onPath: Path? = null,
        connect: String = "",
    ): Pair<Int, String> {
        val process = ProcessBuilder(listOf(script.toString()) + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()[ByBpdWrapper.ENV_BPD] = besideBy?.toString().orEmpty()
                environment()[ByBpdWrapper.ENV_BPD_FALLBACK] = onPath?.toString().orEmpty()
                environment()[ByBpdWrapper.ENV_PORT] = "51234"
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
                environment()[ByBpdWrapper.ENV_CONNECT] = connect
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the wrapper did not exit")
        return process.exitValue() to output
    }

    @Test
    fun `the interpreter and the program are recorded and bpd is started, sharing one file`(@TempDir dir: Path) {
        val script = wrapper(dir)
        val bpd = stub(dir.resolve("toolchain/bpd"))
        val record = dir.resolve("record")

        val (code, output) = run(
            dir, script, record,
            listOf("/project/.venv/bin/python3", "_by_runner.py", "demo", "--flag", "two words"),
            besideBy = bpd,
        )

        assertEquals(0, code, output)
        val parsed = ByBpdRecord.parse(Files.readString(record))
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, parsed) { "record was:\n$parsed" }

        // the interpreter is `by run`'s choice, and is not part of the program's argv
        assertEquals("/project/.venv/bin/python3", ready.python)
        // an argument with a space is one argument, which is the whole reason the record is lines
        assertEquals(listOf("_by_runner.py", "demo", "--flag", "two words"), ready.argv)
        assertEquals(dir.toRealPath().toString(), Path.of(ready.cwd).toRealPath().toString())
        assertEquals(51234, ready.port)
        assertEquals("tok", ready.token)
        assertEquals("x-bpd-token", ready.tokenHeader)
    }

    /**
     * bpd stays where `by run` started the wrapper, which is the directory the program runs in — the
     * one the user ran it from, as for a run with no debugger. The wrapper used to `cd` to the
     * runner's directory, which made the staged tree the program's working directory, and in a run in
     * place `by`'s run cache. The runner is named absolutely and recorded so, which is where bpd finds
     * the map, and where hot reload reads the run's directory from.
     */
    @Test
    fun `bpd runs the program where by run started it, not where the runner is`(@TempDir dir: Path) {
        val script = wrapper(dir)
        val tree = Files.createDirectory(dir.resolve("transpiled"))
        Files.writeString(tree.resolve("_by_runner.py"), "")
        val project = Files.createDirectory(dir.resolve("project"))
        val bpd = stub(
            dir.resolve("toolchain/bpd"),
            """printf 'PWD %s\n' "${'$'}PWD"""" + "\necho '$announcement'",
        )
        val record = dir.resolve("record")

        run(project, script, record, listOf("python3", tree.resolve("_by_runner.py").toString(), "demo"), besideBy = bpd)

        val written = Files.readString(record)
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, ByBpdRecord.parse(written)) {
            "record was:\n$written"
        }
        assertEquals(
            project.toRealPath().toString(),
            Path.of(ready.cwd).toRealPath().toString(),
            "the wrapper recorded a directory that is not the one `by run` started it in: $written",
        )
        assertTrue(
            written.contains("PWD ${ready.cwd}"),
            "bpd was started somewhere other than the directory recorded for it: $written",
        )
        assertEquals(
            tree.toRealPath().toString(),
            ByBpdRecord.runnerDirectoryIn(written)?.let { Path.of(it).toRealPath().toString() },
            "the runner's directory is read off the runner's path: $written",
        )
    }

    @Test
    fun `on a run target bpd dials the IDE, and only what by run chose is recorded`(@TempDir dir: Path) {
        // there the IDE listens: bpd connects out to the address it was given, with the token in
        // its environment, and announces nothing for the record to carry
        val script = wrapper(dir)
        val bpd = stub(dir.resolve("toolchain/bpd"), """echo "ARGS $*"""")
        val record = dir.resolve("record")

        val (_, output) = run(
            dir, script, record, listOf("python3", "_by_runner.py", "demo"),
            besideBy = bpd, connect = "host.docker.internal:51241",
        )

        assertTrue(output.contains("ARGS dap --connect host.docker.internal:51241"), "bpd was started as: $output")
        val chosen = assertInstanceOf(ByBpdRecord.Chosen::class.java, ByBpdRecord.parse(Files.readString(record), announced = false))
        assertEquals("python3", chosen.python)
        assertEquals(listOf("_by_runner.py", "demo"), chosen.argv)
        assertEquals(dir.toRealPath().toString(), Path.of(chosen.cwd).toRealPath().toString())
    }

    @Test
    fun `bpd is given the port the IDE reserved`(@TempDir dir: Path) {
        // the IDE picks the port so it knows where to connect before anything starts. a wrapper
        // that dropped it would leave bpd on a port nobody is dialling
        val script = wrapper(dir)
        val bpd = stub(dir.resolve("toolchain/bpd"), """echo "ARGS $*"""")
        val record = dir.resolve("record")

        run(dir, script, record, listOf("python3", "_by_runner.py", "demo"), besideBy = bpd)

        val written = Files.readString(record)
        assertTrue(written.contains("ARGS dap --listen 51234"), "bpd was started as: $written")
    }

    @Test
    fun `the bpd beside by wins over the one beside the interpreter and the one on PATH`(@TempDir dir: Path) {
        val script = wrapper(dir)
        val besideBy = stub(dir.resolve("toolchain/bpd"), "echo 'WHO by'\necho '$announcement'")
        stub(dir.resolve("env/bin/bpd"), "echo 'WHO interpreter'\necho '$announcement'")
        val onPath = stub(dir.resolve("path/bpd"), "echo 'WHO path'\necho '$announcement'")
        val record = dir.resolve("record")

        run(
            dir, script, record,
            listOf(dir.resolve("env/bin/python3").toString(), "_by_runner.py", "demo"),
            besideBy = besideBy, onPath = onPath,
        )

        assertTrue(Files.readString(record).contains("WHO by"), Files.readString(record))
    }

    /**
     * A uv launch's executable is `uv`, so nothing is beside `by`: the project's `bpd` is in the
     * environment the program runs on, which is where the interpreter `by run` chose lives. The one
     * on `PATH` is somebody else's toolchain.
     */
    @Test
    fun `with nothing beside by the bpd beside the interpreter wins over the one on PATH`(@TempDir dir: Path) {
        val script = wrapper(dir)
        stub(dir.resolve("project/.venv/bin/bpd"), "echo 'WHO interpreter'\necho '$announcement'")
        val onPath = stub(dir.resolve("path/bpd"), "echo 'WHO path'\necho '$announcement'")
        val record = dir.resolve("record")

        run(
            dir, script, record,
            listOf(dir.resolve("project/.venv/bin/python3").toString(), "_by_runner.py", "demo"),
            onPath = onPath,
        )

        assertTrue(Files.readString(record).contains("WHO interpreter"), Files.readString(record))
    }

    @Test
    fun `a bare interpreter name is not looked beside, and PATH answers`(@TempDir dir: Path) {
        // `dirname python3` is `.`, which is wherever the wrapper happens to stand — not an
        // environment. A `bpd` sitting there must not be taken for the project's
        val script = wrapper(dir)
        stub(dir.resolve("bpd"), "echo 'WHO cwd'\necho '$announcement'")
        val onPath = stub(dir.resolve("path/bpd"), "echo 'WHO path'\necho '$announcement'")
        val record = dir.resolve("record")

        run(dir, script, record, listOf("python3", "_by_runner.py", "demo"), onPath = onPath)

        assertTrue(Files.readString(record).contains("WHO path"), Files.readString(record))
    }

    @Test
    fun `with no bpd anywhere the wrapper says so and starts nothing`(@TempDir dir: Path) {
        val script = wrapper(dir)
        val python = dir.resolve("project/.venv/bin/python3")
        val record = dir.resolve("record")

        val (code, output) = run(dir, script, record, listOf(python.toString(), "_by_runner.py", "demo"))

        assertEquals(127, code, output)
        assertEquals(ByBpdRecord.NoBpd(python.toString()), ByBpdRecord.parse(Files.readString(record)))
    }
}
