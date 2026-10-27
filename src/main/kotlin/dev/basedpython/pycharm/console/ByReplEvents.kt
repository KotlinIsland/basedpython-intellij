package dev.basedpython.pycharm.console

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.HexFormat

private val LOG = Logger.getInstance(ByReplEvents::class.java)

/**
 * What a `by repl --events PORT` session says it reads its standard input for
 * (`docs/basedpython/features/repl.md`, *in an editor*): an input, the rest of an input, or a line
 * for the program — `input()`. Over a pipe the console cannot tell these apart, and they are sent
 * differently: an input framed as a paste, a line for the program as it is.
 *
 * `by repl` connects to [port] on the loopback interface before its session starts and writes one
 * line of json for each event. The first is `{"token": T}`, `T` being [token] as it was handed to
 * it in `BY_REPL_EVENTS_TOKEN`: a loopback port is open to every process on the machine, so a
 * connection that does not present it is closed and another waited for.
 */
internal class ByReplEvents private constructor(private val server: ServerSocket) : Closeable {

    /** What the session's standard input is read for, when it is read. */
    enum class Reading { INPUT, CONTINUATION, PROGRAM }

    /** One line `by repl` sent. */
    sealed interface Event {
        data class Waiting(val reading: Reading) : Event

        /** Input [number], as `<by-input-N>` numbers it, has finished; [ran] when it is part of the session. */
        data class Finished(val number: Int, val ran: Boolean) : Event
    }

    val port: Int get() = server.localPort

    /** What `by repl` has to present, minted for this session alone. */
    val token: String = HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))

    /**
     * Hands each event of the session's connection to [onEvent], on a thread of its own, until the
     * connection or this closes.
     */
    fun listen(onEvent: (Event) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        val lines = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                        if (tokenOf(lines.readLine()) != token) {
                            LOG.warn("a connection to the console's event port did not present the session's token")
                            return@use
                        }
                        while (true) {
                            val line = lines.readLine() ?: break
                            parse(line)?.let(onEvent)
                        }
                        // the session's own connection closes when the session ends: nothing more comes
                        close()
                    }
                }
            } catch (e: IOException) {
                if (!server.isClosed) LOG.info("the console's event connection failed", e)
            }
        }
    }

    override fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            LOG.debug("could not close the console's event port", e)
        }
    }

    companion object {
        /** The variable `by repl` reads [token] from. */
        const val ENV_TOKEN = "BY_REPL_EVENTS_TOKEN"

        private const val TOKEN_BYTES = 32

        /** A port `by repl` can connect to, on the loopback interface only. */
        fun open(): ByReplEvents = ByReplEvents(ServerSocket(0, 1, InetAddress.getLoopbackAddress()))

        /** The token a first line presents, or null for anything else. */
        fun tokenOf(line: String?): String? =
            json(line)?.get("token")?.takeIf { it.isJsonPrimitive }?.asString

        /** The event a line reports, or null for one this does not read: an event from a newer `by` costs nothing. */
        fun parse(line: String): Event? {
            val json = json(line) ?: return null
            json.get("reading")?.takeIf { it.isJsonPrimitive }?.asString?.let { reading ->
                return when (reading) {
                    "input" -> Event.Waiting(Reading.INPUT)
                    "continuation" -> Event.Waiting(Reading.CONTINUATION)
                    "program" -> Event.Waiting(Reading.PROGRAM)
                    else -> null
                }
            }
            val finished = json.get("finished")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
            val ran = json.get("ran")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: return null
            return Event.Finished(finished.asInt, ran)
        }

        private fun json(line: String?): JsonObject? =
            try {
                line?.let(JsonParser::parseString)?.takeIf { it.isJsonObject }?.asJsonObject
            } catch (e: RuntimeException) {
                null
            }
    }
}
