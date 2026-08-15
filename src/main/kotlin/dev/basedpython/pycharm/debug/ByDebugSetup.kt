package dev.basedpython.pycharm.debug

import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.net.NetUtils
import dev.basedpython.pycharm.debug.bpd.ByBpdWrapper
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.env.Executables
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
    /**
     * Which debugger this session is for.
     *
     * The two backends put entirely different things in [bootstrapDir] and reach the debuggee in
     * entirely different ways — one through `PYTHONPATH` and a `sitecustomize.py`, the other
     * through `PYTHON` and a wrapper — so every later step has to know which one it is looking at.
     */
    val backend: ByDebugBackend = ByDebugBackend.DEBUGPY,
    /**
     * The `bpd` binary, when [backend] is [ByDebugBackend.BPD].
     *
     * Resolved at setup rather than at launch, because setup is the first moment that can refuse:
     * a session with no `bpd` should never get as far as running the program.
     */
    val bpd: Path? = null,
    /** The interpreter `by run` would have used, for the wrapper to pass a version probe to. */
    val python: String? = null,
) {
    /** The script `PYTHON` is pointed at, for a [ByDebugBackend.BPD] session. */
    val wrapper: Path get() = wrapperOf(bootstrapDir)

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
        /**
         * Prepare a `bpd` session: a wrapper script, a port, and a file for the two of them to
         * meet in.
         *
         * `bpd` cannot be handed the program from outside. `by run` transpiles into a temp
         * directory, writes `_by_sourcemap.py` beside the generated python, and deletes the tree
         * when the program ends — so the map lives exactly as long as the program, and the only
         * way for a debugger to be in the picture is to *be* the interpreter `by run` starts. See
         * [ByBpdWrapper].
         */
        @Throws(ExecutionException::class)
        fun forBpd(bpd: Path, python: String): ByDebugSetup {
            if (!ByBpdWrapper.isSupported(System.getProperty("os.name").orEmpty())) {
                throw ExecutionException(BasedPythonBundle.message("debug.bpd.error.unsupported"))
            }
            val dir = tempDir()
            val wrapper = dir.resolve("bpd-python")
            try {
                Files.writeString(wrapper, ByBpdWrapper.script())
            } catch (e: IOException) {
                throw ExecutionException(
                    BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""),
                    e,
                )
            }
            if (!Executables.makeExecutable(wrapper)) {
                throw ExecutionException(
                    BasedPythonBundle.message("debug.bpd.error.wrapperNotExecutable", wrapper.toString()),
                )
            }
            return ByDebugSetup(
                port = freePort(),
                bootstrapDir = dir,
                infoFile = dir.resolve("bpd-record"),
                backend = ByDebugBackend.BPD,
                bpd = bpd,
                python = python,
            )
        }

        /** The wrapper `by run` is pointed at, which only a bpd session has. */
        private fun wrapperOf(dir: Path): Path = dir.resolve("bpd-python")

        @Throws(ExecutionException::class)
        private fun tempDir(): Path = try {
            FileUtil.createTempDirectory("basedpython-debug", null, true).toPath()
        } catch (e: IOException) {
            throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
        }

        @Throws(ExecutionException::class)
        private fun freePort(): Int = try {
            NetUtils.findAvailableSocketPort()
        } catch (e: IOException) {
            throw ExecutionException(BasedPythonBundle.message("debug.error.noPort", e.message ?: ""), e)
        }

        @Throws(ExecutionException::class)
        fun create(): ByDebugSetup {
            val dir = tempDir()
            val bootstrap = ByDebugSetup::class.java.getResourceAsStream(BOOTSTRAP_RESOURCE)
                ?: throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapMissing", BOOTSTRAP_RESOURCE))
            try {
                bootstrap.use { Files.copy(it, dir.resolve("sitecustomize.py")) }
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
            }
            return ByDebugSetup(freePort(), dir, dir.resolve("debug-info.json"))
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
