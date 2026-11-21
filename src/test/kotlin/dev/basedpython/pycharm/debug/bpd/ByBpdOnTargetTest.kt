package dev.basedpython.pycharm.debug.bpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The IDE's end of `bpd dap --connect`, over a real socket: the first message is bpd's
 * `bpd/connected` event with the token on a header (bpd 95641f8, `docs/development/dap.md`,
 * *connecting out*), read to its end and no further, so the next byte is the session's.
 */
class ByBpdOnTargetTest {

    private val event = """{"body":{"version":"0.0.1-a3"},"event":"bpd/connected","seq":1,"type":"event"}"""

    /** What bpd writes first, presenting [token], followed by [after]. */
    private fun connected(token: String?, after: String): ByteArray {
        val header = buildString {
            append("Content-Length: ${event.length}\r\n")
            if (token != null) append("x-bpd-token: $token\r\n")
            append("\r\n")
        }
        return (header + event + after).toByteArray(StandardCharsets.US_ASCII)
    }

    /** The token [ByBpdConnection.presentedToken] reads off a connection that wrote [bytes], and what comes after it. */
    private fun read(bytes: ByteArray): Pair<String?, String> =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { client ->
                client.getOutputStream().apply { write(bytes); flush() }
                client.shutdownOutput()
                server.accept().use { accepted ->
                    val token = ByBpdConnection.presentedToken(accepted)
                    token to accepted.getInputStream().readAllBytes().toString(StandardCharsets.US_ASCII)
                }
            }
        }

    @Test
    fun `the token is read off bpd's first message, and the session starts after it`() {
        val next = "Content-Length: 2\r\n\r\n{}"
        val (token, rest) = read(connected("a".repeat(64), next))
        assertEquals("a".repeat(64), token)
        assertEquals(next, rest, "the first message is bpd's and is not passed on; what follows is the session's")
    }

    @Test
    fun `a first message without the token presents none`() {
        assertNull(read(connected(null, "")).first)
    }

    @Test
    fun `a connection that sends no message presents none`() {
        assertNull(read("garbage with no header end".toByteArray()).first)
    }

    @Test
    fun `the token the IDE chooses is 64 hex digits, new each time`() {
        val one = ByBpdOnTarget.token()
        assertTrue(Regex("[0-9a-f]{64}").matches(one), one)
        assertTrue(one != ByBpdOnTarget.token())
    }

    @Test
    fun `a session's wrapper and record directory are its own, and its listener is loopback`(@TempDir dir: Path) {
        val onTarget = ByBpdOnTarget.create(dir)
        try {
            assertTrue(onTarget.server.inetAddress.isLoopbackAddress)
            assertTrue(dir.resolve("launcher/bpd-python").toFile().canExecute())
            assertEquals(dir.resolve("record"), onTarget.recordDir)
        } finally {
            onTarget.close()
        }
    }
}
