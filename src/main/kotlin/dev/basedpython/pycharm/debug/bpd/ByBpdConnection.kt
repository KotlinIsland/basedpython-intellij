package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.connection.DebugAdapterHandle
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val LOG = Logger.getInstance(ByBpdConnection::class.java)

/** How long to wait for `by run` to transpile, start the wrapper, and for `bpd` to announce. */
private val READY_TIMEOUT: Duration = 3.minutes
private val POLL_INTERVAL: Duration = 100.milliseconds

/**
 * The socket a `bpd dap --listen` session is spoken over.
 *
 * ## Why a socket rather than the adapter's pipes
 *
 * The platform's `CommandLineDebugAdapterHandle` would speak to an adapter on its own stdin and
 * stdout — but here the IDE does not start the adapter. `by run` does, through the wrapper, three
 * processes down, and its pipes belong to `by run`. A loopback socket is the only channel that
 * reaches across that.
 *
 * It is also the transport `startDebugging` needs: DAP hands a forked child to a client by asking
 * it to open a *second* connection, and nothing can open a second connection to somebody else's
 * pipes.
 *
 * ## The token
 *
 * A loopback port that runs the debuggee's code is reachable by every other process on the
 * machine, so `bpd` mints a token and refuses a connection that does not present it as a header on
 * its first message. There is no way to ask the platform's lsp4j launcher to add one — so it is
 * written onto the socket here, before the launcher writes anything. A DAP header block is
 * `Name: value` lines ended by a blank line, and `Content-Length` was never required to be first,
 * so the token on the line above it is one header block with two headers rather than two messages.
 */
class ByBpdConnection private constructor(
    private val socket: Socket,
    /** What the wrapper and `bpd` recorded, which is also what the session is launched with. */
    val record: ByBpdRecord.Launch,
) : DebugAdapterHandle {

    override val input: InputStream = socket.getInputStream()

    override val output: OutputStream = socket.getOutputStream()

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching { socket.close() }
                .onFailure { LOG.warn("the bpd adapter's socket would not close", it) }
        }
        // and nothing more: `by run` is the session's process, which the platform's own end of the
        // session ends — Stop by killing it, the program finishing by leaving it to exit with the
        // program's code, which a kill from here would replace with SIGKILL's
    }

    companion object {

        /**
         * Wait for `bpd dap --connect` to dial [onTarget]'s listener from the run target, check the
         * token on its first message, then read back what the wrapper recorded.
         *
         * `bpd`'s first message is a `bpd/connected` event carrying the token on the
         * [ByBpdWrapper.TOKEN_HEADER] header. It is read here, byte by byte so that nothing after
         * it is taken from the stream, compared in constant time, and not passed on: what follows
         * is the session, which the platform's DAP client begins with its `initialize`. A
         * connection with another token, or none, is closed and the wait goes on — whoever reached
         * the port first is not served for having done so.
         *
         * A `by run` that ends first says why, as for [open]: a `bpd` without `--connect`
         * (0.0.1-a3) refused the flag and exited 2 with clap's own sentence, which is what the user
         * reads rather than a wait that never ends.
         */
        @Throws(ExecutionException::class)
        internal suspend fun accept(
            onTarget: ByBpdOnTarget,
            debuggee: ProcessHandler?,
            said: ByProcessTail? = null,
            timeout: Duration = READY_TIMEOUT,
        ): ByBpdConnection {
            val server = onTarget.server
            var waited = Duration.ZERO
            while (true) {
                val socket = withContext(Dispatchers.IO) {
                    server.soTimeout = POLL_INTERVAL.inWholeMilliseconds.toInt()
                    try {
                        server.accept()
                    } catch (_: java.net.SocketTimeoutException) {
                        null
                    }
                }
                if (socket != null) {
                    val presented = withContext(Dispatchers.IO) { presentedToken(socket) }
                    if (presented != null && java.security.MessageDigest.isEqual(
                            presented.toByteArray(StandardCharsets.US_ASCII),
                            onTarget.token.toByteArray(StandardCharsets.US_ASCII),
                        )
                    ) {
                        onTarget.close()
                        val record = withContext(Dispatchers.IO) { onTarget.readRecord() }
                            ?.let { ByBpdRecord.parse(it, announced = false) }
                        val launch = record as? ByBpdRecord.Launch
                        if (launch == null) {
                            closeQuietly(socket)
                            val why = (record as? ByBpdRecord.Incomplete)?.why ?: NOTHING_WRITTEN
                            throw ExecutionException(BasedPythonBundle.message("debug.bpd.error.exited", why))
                        }
                        return ByBpdConnection(socket, launch)
                    }
                    LOG.warn("a connection to the debug listener presented no token or the wrong one; closed")
                    closeQuietly(socket)
                }
                if (debuggee?.isProcessTerminated == true) {
                    onTarget.close()
                    throw ExecutionException(exited(debuggee.exitCode, said?.text().orEmpty(), NOTHING_DIALLED))
                }
                waited += POLL_INTERVAL
                if (waited >= timeout) {
                    onTarget.close()
                    throw ExecutionException(BasedPythonBundle.message("debug.bpd.error.timeout", timeout.toString(), NOTHING_DIALLED))
                }
            }
        }

        /**
         * The token on the first message of [socket], read to the end of that message and no
         * further; null when the message is not one, or has no token. Bounded, so a peer that sends
         * nothing or too much cannot hold the wait.
         */
        internal fun presentedToken(socket: Socket): String? {
            socket.soTimeout = FIRST_MESSAGE_TIMEOUT_MS
            val input = socket.getInputStream()
            val headers = StringBuilder()
            try {
                while (!headers.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0 || headers.length > MAX_HEADERS) return null
                    headers.append(byte.toChar())
                }
                var length = -1
                var token: String? = null
                for (line in headers.lines()) {
                    val colon = line.indexOf(':')
                    if (colon < 0) continue
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) length = value.toIntOrNull() ?: -1
                    if (name.equals(ByBpdWrapper.TOKEN_HEADER, ignoreCase = true)) token = value
                }
                if (length !in 0..MAX_FIRST_MESSAGE) return null
                // the `bpd/connected` event itself, which is bpd's and not the session's
                if (input.readNBytes(length).size != length) return null
                socket.soTimeout = 0
                return token
            } catch (_: IOException) {
                return null
            }
        }

        /**
         * Wait for the record, then connect to what it names.
         *
         * Waiting on the file rather than on the port, for the reason the debugpy backend waits on
         * one: `by run` transpiles the whole project before any interpreter starts, which can take
         * far longer than a connect-retry budget, and a wrapper that failed would never open a
         * port at all. The file carries a reason; a refused connection does not.
         */
        @Throws(ExecutionException::class)
        suspend fun open(
            record: Path,
            debuggee: ProcessHandler?,
            said: ByProcessTail? = null,
            timeout: Duration = READY_TIMEOUT,
        ): ByBpdConnection {
            val ready = await(record, debuggee, said, timeout)
            return connect(ready) { debuggee?.destroyProcess() }
        }

        /**
         * A second connection to the `bpd dap --listen` a session of [parent] already speaks to, for
         * a child of its program that bpd asked the IDE to take up (`startDebugging`, see
         * [ByChildSession]).
         *
         * The parent's address and token rather than the ones the reverse request named: bpd says in
         * the request where it listens, and it is the listener the parent connected to — one
         * listener and one token for every session of a debuggee — so what the request names is
         * checked against the parent's record before this is reached
         * ([ByChildSession.parse]), and the connection is made to what the IDE already trusted.
         * Nothing is destroyed when it fails: the program is the parent's, and its session goes on.
         */
        @Throws(ExecutionException::class)
        internal suspend fun child(parent: ByBpdRecord.Ready): ByBpdConnection = connect(parent) {}

        @Throws(ExecutionException::class)
        private suspend fun connect(ready: ByBpdRecord.Ready, onFailure: () -> Unit): ByBpdConnection {
            val socket = try {
                withContext(Dispatchers.IO) {
                    Socket().apply {
                        connect(InetSocketAddress(ready.host, ready.port))
                        // Before anything the launcher writes: the launcher's first write is the
                        // `initialize` the token has to arrive with
                        getOutputStream().write(
                            "${ready.tokenHeader}: ${ready.token}\r\n"
                                .toByteArray(StandardCharsets.US_ASCII),
                        )
                        getOutputStream().flush()
                    }
                }
            } catch (failed: IOException) {
                onFailure()
                throw ExecutionException(
                    BasedPythonBundle.message(
                        "debug.bpd.error.connect",
                        ready.host,
                        // as text: `MessageFormat` groups a number by locale, so a port arrives
                        // as `50,488` and the address it forms is one nobody can dial
                        ready.port.toString(),
                        failed.message ?: failed.toString(),
                    ),
                    failed,
                )
            }

            return ByBpdConnection(socket, ready)
        }

        /**
         * Poll until the record is complete, the program dies, or the wait runs out.
         *
         * A dead program gets one last read, taken the way every other read is: a wrapper that
         * failed does not block, so the record may hold the reason by the time the process is gone —
         * and a wrapper that found no `bpd` writes that and exits in the same instant, which is the
         * read most likely to be this one. When the record explains nothing, what `by run` itself
         * said ([said]) is the reason, because `by run` ending before the wrapper ran is `by run`
         * refusing to start the program.
         */
        @Throws(ExecutionException::class)
        private suspend fun await(
            record: Path,
            debuggee: ProcessHandler?,
            said: ByProcessTail?,
            timeout: Duration,
        ): ByBpdRecord.Ready {
            var waited = Duration.ZERO
            var last = NOTHING_WRITTEN
            while (true) {
                val ended = debuggee?.isProcessTerminated == true
                when (val parsed = read(record)) {
                    is ByBpdRecord.Ready -> return parsed
                    // what a record without an announcement reads as, which is not this one
                    is ByBpdRecord.Chosen -> Unit
                    is ByBpdRecord.Incomplete -> last = parsed.why
                    // the wrapper has exited and said why; waiting on would only wait out the clock
                    is ByBpdRecord.NoBpd -> throw ExecutionException(
                        BasedPythonBundle.message("debug.bpd.error.notFound", parsed.python),
                    )
                    null -> Unit
                }
                // read after the process was seen to have ended, so this read saw everything the
                // wrapper will ever write
                if (ended) {
                    throw ExecutionException(exited(debuggee.exitCode, said?.text().orEmpty(), last))
                }
                if (waited >= timeout) {
                    throw ExecutionException(
                        BasedPythonBundle.message("debug.bpd.error.timeout", timeout.toString(), last),
                    )
                }
                delay(POLL_INTERVAL)
                waited += POLL_INTERVAL
            }
        }

        /**
         * Why the session cannot start, when `by run` ended on its own before the record was
         * complete; null when it has not ended, was stopped, or completed the record first.
         *
         * For the start that [open] never gets to finish: the platform stops a debug session the
         * moment the process it started ends, which cancels [open]'s wait — usually before its next
         * poll could have seen the end and said why, and sometimes before [open] was called at all.
         * The record is read here the way [await] reads it, so both routes to the same end say the
         * same thing.
         */
        fun endedBeforeAttaching(record: Path, said: ByProcessTail): String? {
            val code = said.endedOnItsOwnWith ?: return null
            return when (val parsed = read(record)) {
                is ByBpdRecord.Launch -> null
                is ByBpdRecord.NoBpd -> BasedPythonBundle.message("debug.bpd.error.notFound", parsed.python)
                is ByBpdRecord.Incomplete -> exited(code, said.text(), parsed.why)
                null -> exited(code, said.text(), NOTHING_WRITTEN)
            }
        }

        /**
         * Closes a connection that is not the session's, off the caller's thread since a close can
         * block, and whether or not the caller is being cancelled, which would otherwise skip it.
         */
        private suspend fun closeQuietly(socket: Socket) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { socket.close() } }
        }

        /**
         * Why a session whose process ended cannot start: what the process said and how it
         * exited, or — when it said nothing — what the record was missing.
         */
        private fun exited(code: Int?, said: String, missing: String): String =
            if (said.isEmpty()) {
                BasedPythonBundle.message("debug.bpd.error.exited", missing)
            } else {
                BasedPythonBundle.message("debug.bpd.error.exitedSaying", code?.toString() ?: "unknown", said)
            }

        private const val NOTHING_WRITTEN = "the wrapper has not written anything yet"
        private const val NOTHING_DIALLED = "bpd has not connected to the IDE yet"
        private const val FIRST_MESSAGE_TIMEOUT_MS = 10_000
        private const val MAX_HEADERS = 4_096
        private const val MAX_FIRST_MESSAGE = 65_536

        /** The record as it stands, or `null` when there is not a file there yet. */
        private fun read(record: Path): ByBpdRecord? = try {
            if (Files.isRegularFile(record)) ByBpdRecord.parse(Files.readString(record)) else null
        } catch (_: IOException) {
            // a partially written file reads as "not yet" rather than as a failure, so the next
            // poll can pick it up
            null
        }
    }
}
