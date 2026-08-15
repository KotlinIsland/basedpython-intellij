package dev.basedpython.pycharm.debug.bpd

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The whole chain, with both real binaries: `by run` → the wrapper → `bpd dap` → this plugin.
 *
 * Every other test here stands one process in for another. `ByBpdWrapperExecutionTest` gives the
 * wrapper a stand-in `bpd`; `ByBpdConnectionTest` gives the connection a stand-in listener;
 * `ByRunDrivesTheWrapperTest` uses a real `by` and a stand-in everything else. Each of those
 * proves one join. None of them proves the joins agree.
 *
 * This one starts a real `by run`, which starts the real wrapper, which starts a real
 * `bpd dap --listen`, and then connects to it with [ByBpdConnection] — the plugin's own code — and
 * completes a DAP `initialize`. If any link in that chain is wrong, this is where it shows.
 *
 * **Skipped unless both binaries are named.** `BASEDPYTHON_BY_UNDER_TEST` and
 * `BASEDPYTHON_BPD_UNDER_TEST`, deliberately not `PATH` — see [ByRunDrivesTheWrapperTest] for why
 * a `by` on `PATH` breaks eight unrelated tests in this suite.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the wrapper is a shell script; Windows is refused by name")
class ByBpdLiveSessionTest {

    private companion object {
        const val BY = "BASEDPYTHON_BY_UNDER_TEST"
        const val BPD = "BASEDPYTHON_BPD_UNDER_TEST"

        /** The interpreter `bpd` debugs. Its own minimum is 3.13. */
        const val PYTHON = "BASEDPYTHON_PYTHON_UNDER_TEST"
    }

    private fun binary(variable: String): Path? = System.getenv(variable)
        ?.let { Path.of(it) }
        ?.takeIf { Files.isExecutable(it) }

    private fun executable(dir: Path, name: String, body: String): Path {
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n")
        script.toFile().setExecutable(true)
        return script
    }

    @Test
    fun `a real by run starts a real bpd that this plugin can speak DAP to`(@TempDir dir: Path) {
        val by = binary(BY)
        val bpd = binary(BPD)
        assumeTrue(by != null && bpd != null, "set $BY and $BPD to run the whole chain")

        Files.writeString(
            dir.resolve("demo.by"),
            """
            def main():
                limit = 5
                if limit > 100:
                    print("over")
                print(limit)
            """.trimIndent() + "\n",
        )

        val wrapper = dir.resolve("bpd-python")
        Files.writeString(wrapper, ByBpdWrapper.script())
        wrapper.toFile().setExecutable(true)

        val python = System.getenv(PYTHON) ?: "python3"
        val record = dir.resolve("record")
        val port = java.net.ServerSocket(0).use { it.localPort }

        val byRun = ProcessBuilder(by.toString(), "run", "demo")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("by-run.log").toFile())
            .apply {
                environment()["PYTHON"] = wrapper.toString()
                environment()[ByBpdWrapper.ENV_PYTHON] =
                    executable(dir, "python", """exec $python "${'$'}@"""").toString()
                environment()[ByBpdWrapper.ENV_BPD] = bpd.toString()
                environment()[ByBpdWrapper.ENV_PORT] = port.toString()
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()

        try {
            // the plugin's own connection: it waits for the record, reads where bpd bound, and
            // presents the token before anything the protocol writes
            val connection = runBlocking { ByBpdConnection.open(record, debuggee = null) }

            try {
                // a real DAP `initialize`, framed the way a client frames one. bpd checks the
                // token before it acts on this, so a reply at all means the handshake was accepted
                val request = """{"seq":1,"type":"request","command":"initialize",""" +
                    """"arguments":{"adapterID":"bpd","clientID":"basedpython-pycharm"}}"""
                val body = request.toByteArray(StandardCharsets.UTF_8)
                connection.output.write(
                    "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                connection.output.write(body)
                connection.output.flush()

                val reply = readMessage(connection.input)
                assertTrue(
                    reply.contains("\"success\":true"),
                    "bpd refused the plugin's initialize, so the token or the framing is wrong: $reply",
                )
                assertTrue(
                    reply.contains("\"command\":\"initialize\""),
                    "the reply is not to the request that was sent: $reply",
                )
            } finally {
                runBlocking { connection.disconnect() }
            }
        } finally {
            byRun.destroy()
            byRun.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** One DAP message: a header block, a blank line, then exactly `Content-Length` bytes. */
    private fun readMessage(input: java.io.InputStream): String {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0) { "the adapter closed before it answered. it said: $header" }
            header.append(byte.toChar())
        }
        val length = Regex("""Content-Length:\s*(\d+)""")
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("no Content-Length in the adapter's reply: $header")

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            check(n > 0) { "the adapter closed part way through a $length byte message" }
            read += n
        }
        return String(body, StandardCharsets.UTF_8)
    }
}
