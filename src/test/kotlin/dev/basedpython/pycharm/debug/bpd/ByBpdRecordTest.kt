package dev.basedpython.pycharm.debug.bpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
            cwd /tmp/by-build-1
            arg _by_runner.py
            arg demo
            arg --verbose
            $announcement
            """.trimIndent(),
        )
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, record)
        assertEquals("/tmp/by-build-1", ready.cwd)
        assertEquals(listOf("_by_runner.py", "demo", "--verbose"), ready.argv)
        assertEquals(51234, ready.port)
        assertEquals("x-bpd-token", ready.tokenHeader)
        assertEquals("abc", ready.token)
    }

    @Test
    fun `the wrapper's half without bpd's is not yet rather than broken`() {
        val record = ByBpdRecord.parse("cwd /tmp/x\narg _by_runner.py\n")
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
            "cwd /tmp/x\narg _by_runner.py\n{\"listening\":{\"host\":\"127.0.0.1\",\"port\":1}}\n",
        )
        val incomplete = assertInstanceOf(ByBpdRecord.Incomplete::class.java, record)
        assertTrue(incomplete.why.contains("token"), incomplete.why)
    }

    @Test
    fun `a path with a space survives, because the record is lines rather than a split`() {
        val record = ByBpdRecord.parse(
            "cwd /tmp/a b/c\narg _by_runner.py\narg my module\n$announcement\n",
        )
        val ready = assertInstanceOf(ByBpdRecord.Ready::class.java, record)
        assertEquals("/tmp/a b/c", ready.cwd)
        assertEquals(listOf("_by_runner.py", "my module"), ready.argv)
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
    fun `a version probe is passed through to the real interpreter`() {
        val script = ByBpdWrapper.script()
        assertTrue(script.contains("-c|-m|-V|--version"), script)
        assertTrue(script.contains("exec \"\$${ByBpdWrapper.ENV_PYTHON}\""), script)
    }

    @Test
    fun `the record is truncated and the announcement appended, so both survive`() {
        val script = ByBpdWrapper.script()
        assertTrue(script.contains("> \"\$${ByBpdWrapper.ENV_RECORD}\""), script)
        assertTrue(script.contains(">> \"\$${ByBpdWrapper.ENV_RECORD}\""), script)
    }

    @Test
    fun `the prefixes the script writes are the ones the parser reads`() {
        val script = ByBpdWrapper.script()
        assertTrue(script.contains("'${ByBpdWrapper.CWD_PREFIX}%s\\n'"), script)
        assertTrue(script.contains("'${ByBpdWrapper.ARG_PREFIX}%s\\n'"), script)
    }

    @Test
    fun `windows is refused by name rather than left to fail somewhere else`() {
        assertTrue(!ByBpdWrapper.isSupported("Windows 11"))
        assertTrue(ByBpdWrapper.isSupported("Mac OS X"))
        assertTrue(ByBpdWrapper.isSupported("Linux"))
    }
}
