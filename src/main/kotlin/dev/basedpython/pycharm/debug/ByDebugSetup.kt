package dev.basedpython.pycharm.debug

import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.net.NetUtils
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Everything one debug session needs to agree on between the process it launches and the socket it
 * later attaches to: which port the debuggee listens on, where it writes [ByDebuggeeInfo], and the
 * directory holding the `sitecustomize.py` that does both.
 *
 * Created by `ByDapLaunchArgumentsProvider` (the first thing the DAP runner calls, and the only
 * place that knows the port early enough to put it in the `attach` arguments) and handed to
 * `ByDebugAdapterDescriptor` through [KEY] on the run profile.
 */
class ByDebugSetup(
    val port: Int,
    val bootstrapDir: Path,
    val infoFile: Path,
) {
    companion object {
        val KEY: Key<ByDebugSetup> = Key.create("basedpython.debug.setup")

        const val ENV_PORT: String = "BASEDPYTHON_DEBUG_PORT"
        const val ENV_INFO_OUT: String = "BASEDPYTHON_DEBUG_INFO_OUT"

        private const val BOOTSTRAP_RESOURCE = "/debug/sitecustomize.py"

        /**
         * Allocates a port and unpacks the bootstrap into a fresh temp directory.
         *
         * The bootstrap has to be a real file on disk rather than a `-c` preamble or a `-X`
         * option because `by run` builds the interpreter command line itself: the only thing the
         * IDE controls is the environment, and `PYTHONPATH` plus a `sitecustomize.py` is the one
         * hook that reaches an interpreter you did not launch.
         */
        @Throws(ExecutionException::class)
        fun create(): ByDebugSetup {
            val dir = try {
                FileUtil.createTempDirectory("basedpython-debug", null, true).toPath()
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
            }
            val bootstrap = ByDebugSetup::class.java.getResourceAsStream(BOOTSTRAP_RESOURCE)
                ?: throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapMissing", BOOTSTRAP_RESOURCE))
            try {
                bootstrap.use { Files.copy(it, dir.resolve("sitecustomize.py")) }
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
            }
            val port = try {
                NetUtils.findAvailableSocketPort()
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.noPort", e.message ?: ""), e)
            }
            return ByDebugSetup(port, dir, dir.resolve("debug-info.json"))
        }
    }
}

/** How long to wait for the debuggee to report in before giving up. */
private val READY_TIMEOUT: Duration = 3.minutes
private val POLL_INTERVAL: Duration = 100.milliseconds

/**
 * Waits for the bootstrap to write [infoFile], polling until [processAlive] goes false or
 * [timeout] elapses.
 *
 * Waiting on the file rather than on the port is deliberate. `by run` transpiles the whole project
 * before the interpreter ever starts, which can take far longer than any reasonable connect-retry
 * budget, and a debuggee that failed to import `debugpy` would never open a port at all — polling
 * the socket could only ever report "timed out", while the file carries the actual reason.
 *
 * One last read happens after the process dies: a bootstrap that reported an error does not block,
 * so the program runs on and exits normally with the file already in place.
 */
suspend fun awaitDebuggeeInfo(
    infoFile: Path,
    timeout: Duration = READY_TIMEOUT,
    processAlive: () -> Boolean,
): ByDebuggeeInfo? {
    var waited = Duration.ZERO
    while (true) {
        readDebuggeeInfo(infoFile)?.let { return it }
        if (!processAlive()) return readDebuggeeInfo(infoFile)
        if (waited >= timeout) return null
        delay(POLL_INTERVAL)
        waited += POLL_INTERVAL
    }
}

/**
 * The bootstrap writes through a temp file and a rename, so a readable file is a complete one —
 * but a rename is only atomic within a filesystem, and an unparsable read is treated as "not there
 * yet" rather than as a failure so the next poll can pick it up.
 */
private fun readDebuggeeInfo(infoFile: Path): ByDebuggeeInfo? =
    try {
        if (Files.isRegularFile(infoFile)) ByDebuggeeInfo.parse(Files.readString(infoFile)) else null
    } catch (_: IOException) {
        null
    }
