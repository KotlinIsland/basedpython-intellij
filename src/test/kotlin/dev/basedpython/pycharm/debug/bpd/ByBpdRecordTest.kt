package dev.basedpython.pycharm.debug.bpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The handshake between three processes that cannot talk to each other directly.
 *
 * `by run` chooses a temp directory, the wrapper records it, `bpd` appends where it bound, and the
 * IDE reads both out of one file. Every stage of that is somebody else's process, so what can be
 * tested here is the parsing — which is also where a half-written file has to read as "not yet"
 * rather than as a failure, because that is its ordinary state for the first second of a session.
 */
class ByBpdRecordTest {

    private val announcement =
        """{"listening":{"host":"127.0.0.1","port":51234,"header":"x-bpd-token","token":"abc"}}"""

    @Test
    fun `a finished record carries what to launch and where to connect`() {
        val record = ByBpdRecord.parse(
            """
            python /project/.venv/bin/python3
            cwd /tmp/by-build-1
            arg _by_runner.py
            arg demo
            arg --verbose
            $announcement
            """.trimIndent(),
        )
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, record)
        assertEquals("/project/.venv/bin/python3", ready.python)
        assertEquals("/tmp/by-build-1", ready.cwd)
        assertEquals(listOf("_by_runner.py", "demo", "--verbose"), ready.argv)
        assertEquals(51234, ready.port)
        assertEquals("x-bpd-token", ready.tokenHeader)
        assertEquals("abc", ready.token)
    }

    /**
     * The program bpd is told to launch is the one `by run` handed the wrapper — the interpreter
     * `by run` discovered, the runner by the path `by run` gave it, then everything after — and not
     * the IDE's idea of any of it. A configuration that names no module runs `run.main`, which only
     * `by run` reads, and the interpreter is whatever `by run`'s own discovery chose.
     */
    @Test
    fun `the launch request names what by run asked for, over what the provider knew`() {
        val ready = assertInstanceOf(
            ByBpdRecord.Ready::class.java,
            ByBpdRecord.parse(
                """
                python /project/.venv/bin/python3
                cwd /tmp/by-build-1
                arg /tmp/by-build-1/_by_runner.py
                arg app.main
                arg --verbose
                $announcement
                """.trimIndent(),
            ),
        )
        assertEquals(
            mapOf(
                "stopOnEntry" to false,
                "python" to "/project/.venv/bin/python3",
                "program" to "/tmp/by-build-1/_by_runner.py",
                "args" to listOf("app.main", "--verbose"),
                // said in the launch, which cannot reach bpd after the program has started, so
                // neither has to overtake the platform's own configuration requests
                "understands" to listOf("bpd/moved", "bpd/restarting", "bpd/recomposition"),
                "watchRecompositions" to true,
            ),
            ready.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = true),
        )
        assertEquals(false, ready.launchArguments(emptyMap(), watchRecompositions = false)["watchRecompositions"])
    }

    @Test
    fun `a record with no interpreter is not yet ready`() {
        val record = ByBpdRecord.parse("cwd /tmp/x\narg _by_runner.py\n$announcement\n")
        val incomplete = assertInstanceOf(ByBpdRecord.Incomplete::class.java, record)
        assertTrue(incomplete.why.contains("interpreter"), incomplete.why)
    }

    @Test
    fun `a wrapper that found no bpd says so, naming the interpreter it looked beside`() {
        val record = ByBpdRecord.parse("nobpd /project/.venv/bin/python3\n")
        assertEquals(ByBpdRecord.NoBpd("/project/.venv/bin/python3"), record)
    }

    @Test
    fun `the wrapper's half without bpd's is not yet rather than broken`() {
        val record = ByBpdRecord.parse("python python3\ncwd /tmp/x\narg _by_runner.py\n")
        val incomplete = assertInstanceOf(ByBpdRecord.Incomplete::class.java, record)
        assertTrue(incomplete.why.contains("listening"), incomplete.why)
    }

    @Test
    fun `an empty file says the wrapper has not run yet`() {
        val incomplete = assertInstanceOf(ByBpdRecord.Incomplete::class.java, ByBpdRecord.parse(""))
        assertTrue(incomplete.why.contains("transpiled into"), incomplete.why)
    }

    @Test
    fun `an announcement missing a field names every field rather than the first`() {
        val record = ByBpdRecord.parse(
            "python python3\ncwd /tmp/x\narg _by_runner.py\n{\"listening\":{\"host\":\"127.0.0.1\",\"port\":1}}\n",
        )
        val incomplete = assertInstanceOf(ByBpdRecord.Incomplete::class.java, record)
        assertTrue(incomplete.why.contains("token"), incomplete.why)
    }

    @Test
    fun `a path with a space survives, because the record is lines rather than a split`() {
        val record = ByBpdRecord.parse(
            "python /tmp/a b/.venv/bin/python3\ncwd /tmp/a b/c\narg _by_runner.py\narg my module\n$announcement\n",
        )
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, record)
        assertEquals("/tmp/a b/.venv/bin/python3", ready.python)
        assertEquals("/tmp/a b/c", ready.cwd)
        assertEquals(listOf("_by_runner.py", "my module"), ready.argv)
    }

    /**
     * The directory of the runner `by run` chose, which hot reload cannot work without: the staged
     * tree's top, or a run in place's run directory, each with `_by_sourcemap.py` beside the runner.
     *
     * Read out of the record on demand rather than held as a value, and this is why: the wrapper
     * writes it only once `by run` has started the program, which is **after** the IDE has built the
     * debug process. A value captured at construction is a read of a file that does not exist, and
     * the first real session did exactly that — every reload answered that the build directory was
     * not known.
     *
     * Not the record's `cwd`: that is the program's working directory, where the user ran it, which
     * the wrapper no longer leaves for the runner's (it used to `cd` there, which made the staged tree
     * or the run cache the program's working directory).
     */
    @Test
    fun `the runner's directory is read out of a finished record`(@TempDir dir: Path) {
        val record = dir.resolve("bpd-record")
        Files.writeString(
            record,
            "python python3\ncwd /p/project\narg /cache/basedpython-run/p-1/runs/9f3e/_by_runner.py\narg --path\narg /p/project\narg main\n$announcement\n",
        )

        assertEquals("/cache/basedpython-run/p-1/runs/9f3e", ByBpdRecord.runnerDirectoryOf(record))
    }

    /**
     * The wrapper's own lines land before `bpd` appends its announcement, so the directory is
     * readable earlier than a whole [ByBpdRecord.Ready] is — which is the point of reading it on its
     * own rather than waiting for the record to be complete.
     */
    @Test
    fun `the runner's directory is readable before bpd has announced itself`(@TempDir dir: Path) {
        val record = dir.resolve("bpd-record")
        Files.writeString(record, "cwd /p/project\narg /var/folders/x/T/.tmpHalf/_by_runner.py\n")

        assertEquals("/var/folders/x/T/.tmpHalf", ByBpdRecord.runnerDirectoryOf(record))
        assertInstanceOf(ByBpdRecord.Incomplete::class.java, ByBpdRecord.parse(Files.readString(record)))
    }

    /** A runner named relatively is in the directory `by run` named it from. */
    @Test
    fun `a relatively named runner is resolved against the working directory`() {
        assertEquals("/p/project/build", ByBpdRecord.runnerDirectoryIn("cwd /p/project\narg build/_by_runner.py\n"))
        assertEquals("/p/project", ByBpdRecord.runnerDirectoryIn("cwd /p/project\narg _by_runner.py\n"))
    }

    /**
     * Null rather than an exception for every way it can be absent — a missing directory costs the
     * reload button and must never cost the session that asked.
     */
    @Test
    fun `a record that is not there or names no runner yet is null`(@TempDir dir: Path) {
        assertEquals(null, ByBpdRecord.runnerDirectoryOf(dir.resolve("never-written")))

        val empty = dir.resolve("empty").also { Files.writeString(it, "") }
        assertEquals(null, ByBpdRecord.runnerDirectoryOf(empty))

        // the wrapper has started writing but the runner's line is not there yet
        val partial = dir.resolve("partial").also { Files.writeString(it, "cwd /p/project\n") }
        assertEquals(null, ByBpdRecord.runnerDirectoryOf(partial))

        // and a relative runner with nowhere to resolve it from is no directory
        val blank = dir.resolve("blank").also { Files.writeString(it, "arg _by_runner.py\n") }
        assertEquals(null, ByBpdRecord.runnerDirectoryOf(blank))
    }
}

/** Which debugger a setting names. */
class ByDebugBackendTest {

    @Test
    fun `bpd is what an absent or unreadable setting means`() {
        assertEquals(ByDebugBackend.BPD, ByDebugBackend.of(null))
        assertEquals(ByDebugBackend.BPD, ByDebugBackend.of(""))
        assertEquals(ByDebugBackend.BPD, ByDebugBackend.of("something-newer"))
        assertEquals(ByDebugBackend.BPD, ByDebugBackend.of("bpd"))
    }

    @Test
    fun `debugpy is reachable and round trips through what is stored`() {
        assertEquals(ByDebugBackend.DEBUGPY, ByDebugBackend.of("debugpy"))
        assertEquals(ByDebugBackend.DEBUGPY, ByDebugBackend.of("  DebugPy "))
        for (backend in ByDebugBackend.entries) {
            assertEquals(backend, ByDebugBackend.of(ByDebugBackend.settingFor(backend)))
        }
    }
}

/** The script `by run` is pointed at. */
class ByBpdWrapperTest {


    @Test
    fun `the record is truncated and the announcement appended, so both survive`() {
        val script = ByBpdWrapper.script()
        assertTrue(script.contains("> \"\$${ByBpdWrapper.ENV_RECORD}\""), script)
        assertTrue(script.contains(">> \"\$${ByBpdWrapper.ENV_RECORD}\""), script)
    }

    @Test
    fun `the prefixes the script writes are the ones the parser reads`() {
        val script = ByBpdWrapper.script()
        assertTrue(script.contains("'${ByBpdWrapper.PYTHON_PREFIX}%s\\n'"), script)
        assertTrue(script.contains("'${ByBpdWrapper.CWD_PREFIX}%s\\n'"), script)
        assertTrue(script.contains("'${ByBpdWrapper.NO_BPD_PREFIX}%s\\n'"), script)
        assertTrue(script.contains("'${ByBpdWrapper.ARG_PREFIX}%s\\n'"), script)
    }

    @Test
    fun `windows is refused by name rather than left to fail somewhere else`() {
        assertTrue(!ByBpdWrapper.isSupported("Windows 11"))
        assertTrue(ByBpdWrapper.isSupported("Mac OS X"))
        assertTrue(ByBpdWrapper.isSupported("Linux"))
    }
}
