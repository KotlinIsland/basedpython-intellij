package dev.basedpython.pycharm.env.manager

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import dev.basedpython.pycharm.ui.log.BasedPythonLog
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What running an [EnvCommand] produced. */
data class EnvResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /**
     * The most useful single line to put in a notification when this failed.
     *
     * uv writes its diagnostics to stderr and its data to stdout, so stderr's last non-blank line is
     * the actual complaint; stdout is the fallback for a tool that does not make that split.
     */
    fun failureMessage(): String =
        stderr.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
            ?: stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
            ?: "exit code $exitCode"

    companion object {
        /** The result for a command that could not be started at all. */
        fun failedToStart(message: String): EnvResult = EnvResult(NOT_STARTED, "", message)

        /** Distinct from any exit code a tool would choose, so "did not run" is never read as "failed". */
        const val NOT_STARTED: Int = -1
    }
}

/**
 * Runs an environment manager's commands.
 *
 * Two modes, and the split is about who is watching. A query ([EnvCommand.isQuery]) is captured and
 * silent: it exists to be parsed, it is run on every refresh, and printing `uv pip list` to the log
 * every few seconds would make the log useless. Everything else is a change to the user's project —
 * a sync, an add, an interpreter download — and streams into the plugin's log tool window as it
 * happens, because those take real time and a progress bar with no output is how a tool that is
 * resolving 200 packages looks identical to one that has hung.
 *
 * Both modes block, and both honour the calling context's cancellation — see [execute]. Callers run
 * them off the EDT.
 */
internal object EnvRunner {

    private val LOG = Logger.getInstance(EnvRunner::class.java)

    /** How long a query gets before it is treated as a failure. */
    private const val QUERY_TIMEOUT_MS = 30_000

    /**
     * Runs [command] for [backend] and returns what it said.
     *
     * [workDir] is the project root — how every backend is told which project it is acting on,
     * rather than a flag that each would spell differently.
     */
    fun run(
        project: Project,
        backend: EnvBackend,
        command: EnvCommand,
        workDir: Path,
        /** Called with each output line as it arrives, for live per-package progress. */
        onLine: (String) -> Unit = {},
    ): EnvResult {
        val exe = EnvTools.find(backend)
            ?: return EnvResult.failedToStart("${backend.executableName} is not installed")
        val cmd = GeneralCommandLine()
            .withExePath(exe.toString())
            .withParameters(command.args)
            .withWorkingDirectory(workDir)
            .withCharset(Charsets.UTF_8)
        return if (command.isQuery) capture(cmd, command, exe) else stream(project, cmd, command, exe, onLine)
    }

    /** Runs captured, with a timeout, printing nothing. */
    private fun capture(cmd: GeneralCommandLine, command: EnvCommand, exe: Path): EnvResult =
        execute(cmd, command.describe(exe.toString()), QUERY_TIMEOUT_MS.toLong()) { _, _ -> }

    /**
     * Runs with output streamed to the plugin's log, and blocks until the process exits.
     *
     * Output is collected as well as printed: a failure notification needs the last line, and the
     * user should not have to go and find it in a console to learn why an add failed.
     */
    private fun stream(
        project: Project,
        cmd: GeneralCommandLine,
        command: EnvCommand,
        exe: Path,
        onLine: (String) -> Unit,
    ): EnvResult {
        val log = BasedPythonLog.getInstance(project)
        log.info("env: ${command.describe(exe.toString())}")
        // Bounded so a tool waiting on a credential prompt we cannot see does not hold the
        // background task — and the operator's own progress indicator — open forever.
        val timeout = TimeUnit.MINUTES.toMillis(OPERATION_TIMEOUT_MINUTES)
        return execute(cmd, command.describe(exe.toString()), timeout) { text, isError ->
            // Trimmed because the log adds its own newline, and a blank trailing line per chunk
            // would double-space everything uv prints.
            text.trimEnd('\n', '\r').takeIf { it.isNotEmpty() }?.let {
                log.serverOutput(exe.fileName.toString(), it, isError)
                // Progress is read from the same lines the log shows, so what the user sees
                // spinning and what the log says can never disagree.
                onLine(it)
            }
        }
    }

    /**
     * Starts [cmd], hands each chunk of output to [onText], and waits for it to exit.
     *
     * The wait is a poll rather than one long block, because the caller is usually a cancellable
     * background task, and a cancel button that leaves `uv sync` running is not a cancel button.
     * Between polls the calling context's cancellation is checked; once it is cancelled the process
     * is destroyed and the cancellation goes on up, rather than being turned into a failed result —
     * a cancelled gesture is not an error to notify anyone about.
     *
     * [timeoutMs] expiring destroys the process too, and reads as "did not run" rather than as an
     * exit code a backend would interpret.
     */
    internal fun execute(
        cmd: GeneralCommandLine,
        description: String,
        timeoutMs: Long,
        onText: (text: String, isError: Boolean) -> Unit,
    ): EnvResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val handler = try {
            OSProcessHandler(cmd)
        } catch (e: Exception) {
            LOG.warn("Failed to run $description", e)
            return EnvResult.failedToStart(e.message ?: e.javaClass.simpleName)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                val isError = outputType == ProcessOutputTypes.STDERR
                synchronized(out) { (if (isError) err else out).append(event.text) }
                onText(event.text, isError)
            }
        })
        handler.startNotify()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (!handler.waitFor(POLL_MS)) {
                ProgressManager.checkCanceled()
                if (System.nanoTime() >= deadline) {
                    stop(handler)
                    return EnvResult.failedToStart("$description timed out")
                }
            }
        } catch (e: Throwable) {
            // Cancellation above all, but anything that ends the wait early: the process must not
            // outlive the task that started it.
            stop(handler)
            throw e
        }
        // The process has exited, but its last output can still be on its way to the listener;
        // waiting for the handler rather than the process is what lets it arrive.
        handler.waitFor()
        return synchronized(out) {
            EnvResult(handler.exitCode ?: EnvResult.NOT_STARTED, out.toString(), err.toString())
        }
    }

    private fun stop(handler: OSProcessHandler) {
        handler.destroyProcess()
        handler.waitFor(STOP_GRACE_MS)
    }

    /** How often a running command looks up to see whether it has been cancelled. */
    private const val POLL_MS = 100L

    /** How long a destroyed process gets to go before the caller stops waiting for it. */
    private const val STOP_GRACE_MS = 2_000L

    /**
     * How long a mutating operation gets.
     *
     * Generous, because the operations behind it legitimately are: `uv python install` downloads and
     * unpacks a CPython build, and a cold `uv sync` on a large project resolves and downloads
     * hundreds of wheels. This is a hang guard, not a performance budget.
     */
    private const val OPERATION_TIMEOUT_MINUTES = 15L
}
