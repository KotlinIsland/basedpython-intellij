package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.connection.DebugAdapterHandle
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
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
    private val debuggee: ProcessHandler?,
) : DebugAdapterHandle {

    override val input: InputStream = socket.getInputStream()

    override val output: OutputStream = socket.getOutputStream()

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            runCatching { socket.close() }
                .onFailure { LOG.warn("the bpd adapter's socket would not close", it) }
        }
        // `by run` is the process the IDE started, so `by run` is the process the IDE ends.
        // Detaching and leaving it running would be the wrong reading of the Stop button for a
        // session that looks and behaves like a launch — the same judgement the debugpy backend
        // makes about the same process
        debuggee?.destroyProcess()
    }

    companion object {

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
            timeout: Duration = READY_TIMEOUT,
        ): ByBpdConnection {
            val ready = await(record, debuggee, timeout)

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
                debuggee?.destroyProcess()
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

            return ByBpdConnection(socket, debuggee)
        }

        /**
         * Poll until the record is complete, the program dies, or the wait runs out.
         *
         * A dead program gets one last read: a wrapper that failed does not block, so the record
         * may already hold the reason by the time the process is gone.
         */
        @Throws(ExecutionException::class)
        private suspend fun await(
            record: Path,
            debuggee: ProcessHandler?,
            timeout: Duration,
        ): ByBpdRecord.Ready {
            var waited = Duration.ZERO
            var last = "the wrapper has not written anything yet"
            while (true) {
                when (val parsed = read(record)) {
                    is ByBpdRecord.Ready -> return parsed
                    is ByBpdRecord.Incomplete -> last = parsed.why
                    null -> Unit
                }
                if (debuggee?.isProcessTerminated == true) {
                    // one last look, then give up naming what was missing
                    (read(record) as? ByBpdRecord.Ready)?.let { return it }
                    throw ExecutionException(
                        BasedPythonBundle.message("debug.bpd.error.exited", last),
                    )
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
