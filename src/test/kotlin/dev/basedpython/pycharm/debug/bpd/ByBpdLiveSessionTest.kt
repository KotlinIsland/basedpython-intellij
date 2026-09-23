package dev.basedpython.pycharm.debug.bpd

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.Executors
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
 * speaks DAP to it the way the platform does. If any link in that chain is wrong, this is where it
 * shows.
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

    @Test
    fun `a real by run starts a real bpd that this plugin can speak DAP to`(@TempDir dir: Path) {
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

        session(dir, module = "demo") { connection, python, dap ->
            // a real DAP `initialize`, framed the way a client frames one. bpd checks the token
            // before it acts on this, so a reply at all means the handshake was accepted
            val reply = dap.request("initialize", mapOf("adapterID" to "bpd", "clientID" to "basedpython-intellij"))
            assertTrue(
                reply.get("success").asBoolean,
                "bpd refused the plugin's initialize, so the token or the framing is wrong: $reply",
            )

            // and the program, launched with what `by run` recorded rather than what the IDE could
            // have guessed: the interpreter `by run` chose, the runner by the path it named, and its
            // arguments
            assertEquals(python, connection.record.python, "the record names the interpreter `by run` chose")
            val arguments = connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false)
            val launched = dap.request("launch", arguments)
            assertTrue(launched.get("success").asBoolean, "bpd would not launch what the record names ($arguments): $launched")
            dap.request("configurationDone", emptyMap())
            dap.until("terminated")
            assertTrue(
                dap.seen.any { it.event == "output" && it.body?.get("output")?.asString?.contains("5") == true },
                "the program ran without printing its `limit`: ${dap.seen}",
            )
        }
    }

    /**
     * What `.by` debugging stands on now that nothing is sent ahead of the platform: every `.by`
     * breakpoint is set **before** the launch — before bpd has read any map, which it does at
     * launch — in the order the platform sends them, and nothing else is sent: no source map, no
     * `bpd/understands`. Each breakpoint is answered `pending`, binds once the map is read and its
     * module imported, and stops the program on the `.by` line, in each of two files.
     */
    @Test
    fun `a by breakpoint set before any map binds and stops, in every file`(@TempDir tempDir: Path) {
        // canonical, because the platform names a file by the path it has on disk and `by run`
        // records the `.by` by the one it was handed
        val dir = tempDir.toRealPath()
        val helper = dir.resolve("helper.by")
        Files.writeString(
            helper,
            """
            def double(value: int) -> int:
                twice = value * 2
                return twice
            """.trimIndent() + "\n",
        )
        val demo = dir.resolve("demo.by")
        Files.writeString(
            demo,
            """
            from helper import double


            def main():
                limit = double(5)
                print(limit)


            main()
            """.trimIndent() + "\n",
        )

        session(dir, module = "demo") { connection, _, dap ->
            dap.request("initialize", mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path"))
            dap.until("initialized")

            // the platform's order: every file's breakpoints, then `configurationDone`, then the
            // launch — nothing of this plugin's in between
            for ((file, line) in listOf(helper to 3, demo to 6)) {
                val set = dap.request(
                    "setBreakpoints",
                    mapOf("source" to mapOf("path" to file.toString()), "breakpoints" to listOf(mapOf("line" to line))),
                )
                assertTrue(set.get("success").asBoolean, "the breakpoint in ${file.fileName} was refused: $set")
                val answer = set.getAsJsonObject("body").getAsJsonArray("breakpoints")[0].asJsonObject
                assertEquals(false, answer.get("verified").asBoolean, "nothing is watching a line before the launch: $answer")
                assertEquals("pending", answer.get("reason")?.asString, "a map not read yet is not a map that will never arrive: $answer")
            }
            dap.request("configurationDone", emptyMap())
            val launched = dap.request(
                "launch",
                connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false),
            )
            assertTrue(launched.get("success").asBoolean, "bpd would not launch: $launched")

            for ((file, line) in listOf(helper to 3, demo to 6)) {
                val stopped = dap.until("stopped")
                assertEquals("breakpoint", stopped.body?.get("reason")?.asString, "$stopped")
                val thread = stopped.body!!.get("threadId").asInt
                val top = dap.request("stackTrace", mapOf("threadId" to thread))
                    .getAsJsonObject("body").getAsJsonArray("stackFrames")[0].asJsonObject
                assertEquals(file.toString(), top.getAsJsonObject("source").get("path").asString, "the stop is in the `.by`: $top")
                assertEquals(line, top.get("line").asInt, "and on the line the breakpoint was set on: $top")
                dap.request("continue", mapOf("threadId" to thread))
            }
            dap.until("terminated")

            val bound = dap.seen.filter { it.event == "breakpoint" }
                .mapNotNull { it.body?.getAsJsonObject("breakpoint") }
                .filter { it.get("verified")?.asBoolean == true }
                .map { it.getAsJsonObject("source").get("path").asString to it.get("line").asInt }
            assertEquals(
                setOf(helper.toString() to 3, demo.toString() to 6),
                bound.toSet(),
                "each held breakpoint is told bound, in `.by` terms",
            )
            assertTrue(
                dap.seen.any { it.event == "output" && it.body?.get("output")?.asString?.contains("10") == true },
                "the program ran on to the end after both stops: ${dap.seen}",
            )
        }
    }

    /**
     * `by run` of [module] in [dir] through the real wrapper, connected with [ByBpdConnection], and
     * [body] run against it. Skips unless both binaries are named.
     */
    private fun session(
        dir: Path,
        module: String,
        body: (connection: ByBpdConnection, python: String, dap: Dap) -> Unit,
    ) {
        val by = binary(BY)
        val bpd = binary(BPD)
        assumeTrue(by != null && bpd != null, "set $BY and $BPD to run the whole chain")

        val wrapper = dir.resolve("bpd-python")
        Files.writeString(wrapper, ByBpdWrapper.script())
        wrapper.toFile().setExecutable(true)

        val python = System.getenv(PYTHON) ?: "python3"
        val record = dir.resolve("record")
        val port = java.net.ServerSocket(0).use { it.localPort }

        // the interpreter is `by run`'s to choose: a project with no environment of its own takes
        // `PYTHON`, and the wrapper is only ever handed what `by run` chose
        val byRun = ProcessBuilder(by.toString(), "run", "--launcher", wrapper.toString(), module)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("by-run.log").toFile())
            .apply {
                environment().remove("VIRTUAL_ENV")
                environment()["PYTHON"] = python
                environment()[ByBpdWrapper.ENV_BPD] = bpd.toString()
                environment()[ByBpdWrapper.ENV_BPD_FALLBACK] = ""
                environment()[ByBpdWrapper.ENV_PORT] = port.toString()
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()

        try {
            // the plugin's own connection: it waits for the record, reads where bpd bound, and
            // presents the token before anything the protocol writes
            val connection = runBlocking { ByBpdConnection.open(record, debuggee = null) }
            val reader = Executors.newSingleThreadExecutor()
            try {
                body(connection, python, Dap(connection, reader))
            } finally {
                reader.shutdownNow()
                runBlocking { connection.disconnect() }
            }
        } finally {
            byRun.destroy()
            byRun.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** One message off the wire, read as the envelope DAP defines. */
    private data class Message(val json: JsonObject) {
        val type: String? get() = json.get("type")?.asString
        val event: String? get() = json.get("event")?.takeIf { type == "event" }?.asString
        val body: JsonObject? get() = json.get("body")?.takeIf { it.isJsonObject }?.asJsonObject
        override fun toString(): String = json.toString()
    }

    /** A client over [connection]: requests answered in order, events kept as they arrive. */
    private inner class Dap(
        private val connection: ByBpdConnection,
        private val reader: java.util.concurrent.ExecutorService,
    ) {
        private var seq = 1
        val seen = mutableListOf<Message>()
        private var taken = 0

        fun request(command: String, arguments: Map<String, Any?>): JsonObject {
            val mine = seq++
            send(connection.output, mine, command, arguments)
            return next { it.type == "response" && it.json.get("request_seq")?.asInt == mine }.json
        }

        /** The next event named [event] not already taken, waiting for it if it has not come. */
        fun until(event: String): Message = next { it.event == event }

        private fun next(wanted: (Message) -> Boolean): Message {
            while (true) {
                while (taken < seen.size) {
                    val message = seen[taken++]
                    if (wanted(message)) return message
                }
                val text = reader.submit<String> { readMessage(connection.input) }.get(60, TimeUnit.SECONDS)
                seen += Message(JsonParser.parseString(text).asJsonObject)
            }
        }
    }

    private fun send(output: java.io.OutputStream, seq: Int, command: String, arguments: Map<String, Any?>) {
        val body = Gson().toJson(mapOf("seq" to seq, "type" to "request", "command" to command, "arguments" to arguments))
            .toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
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
