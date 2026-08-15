package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The connection, over a real socket.
 *
 * What this covers that no unit test can: that the token is on the wire **before** anything else,
 * and that it is the byte sequence `bpd` looks for. Everything about it is a shape agreement with
 * another process — `Name: value\r\n`, ahead of the `Content-Length` the lsp4j launcher writes —
 * and a mistake in it is a session that hangs rather than one that errors.
 */
class ByBpdConnectionTest {

    /** A stand-in `bpd dap --listen`: accepts one client and reports what it said first. */
    private class Listener : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val firstBytes = ArrayBlockingQueue<String>(1)

        init {
            Thread {
                runCatching {
                    server.accept().use { client ->
                        val buffer = ByteArray(64)
                        val read = client.getInputStream().read(buffer)
                        firstBytes.offer(
                            if (read > 0) String(buffer, 0, read, StandardCharsets.US_ASCII) else "",
                        )
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        /** What the client wrote before anything else, or `null` if it wrote nothing in time. */
        fun greeting(): String? = firstBytes.poll(10, TimeUnit.SECONDS)

        override fun close() = server.close()
    }

    private fun record(dir: Path, listener: Listener, token: String = "tok"): Path {
        val file = dir.resolve("record")
        Files.writeString(
            file,
            """
            cwd ${dir.toAbsolutePath()}
            arg _by_runner.py
            arg demo
            {"listening":{"host":"127.0.0.1","port":${listener.port},"header":"x-bpd-token","token":"$token"}}
            """.trimIndent() + "\n",
        )
        return file
    }

    @Test
    fun `the token is the first thing on the socket, ahead of the protocol`(@TempDir dir: Path) {
        Listener().use { listener ->
            val connection = runBlocking {
                ByBpdConnection.open(record(dir, listener, token = "secret-1"), debuggee = null)
            }
            connection.use {
                assertEquals(
                    "x-bpd-token: secret-1\r\n",
                    listener.greeting(),
                    "bpd checks the token before it acts on anything, so it has to arrive first",
                )
            }
        }
    }

    @Test
    fun `a record that never completes is given up on with what was missing`(@TempDir dir: Path) {
        // the wrapper wrote its half and `bpd` never announced — a real failure mode, and the
        // message is the only thing the user gets
        val file = dir.resolve("record")
        Files.writeString(file, "cwd /tmp/x\narg _by_runner.py\n")

        val failed = assertThrows<ExecutionException> {
            runBlocking { ByBpdConnection.open(file, debuggee = null, timeout = 300.milliseconds) }
        }
        assertTrue(
            failed.message.orEmpty().contains("listening"),
            "the refusal should name what was missing: ${failed.message}",
        )
    }

    @Test
    fun `a port nothing is listening on is refused by name`(@TempDir dir: Path) {
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val file = dir.resolve("record")
        Files.writeString(
            file,
            "cwd /tmp/x\narg _by_runner.py\n" +
                """{"listening":{"host":"127.0.0.1","port":$free,"header":"x-bpd-token","token":"t"}}""" + "\n",
        )

        val failed = assertThrows<ExecutionException> {
            runBlocking { ByBpdConnection.open(file, debuggee = null, timeout = 5.seconds) }
        }
        assertTrue(
            failed.message.orEmpty().contains("$free"),
            "the refusal should name where it tried: ${failed.message}",
        )
    }
}

private fun ByBpdConnection.use(block: () -> Unit) {
    try {
        block()
    } finally {
        runBlocking { disconnect() }
    }
}
