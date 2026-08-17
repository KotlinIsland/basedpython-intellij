package dev.basedpython.pycharm.env.manager

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.util.ExecUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.diagnostic.Logger
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
 * Both modes block. Callers run them off the EDT; [EnvService] is the only one that should need to.
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
    private fun capture(cmd: GeneralCommandLine, command: EnvCommand, exe: Path): EnvResult = try {
        val output = ExecUtil.execAndGetOutput(cmd, QUERY_TIMEOUT_MS)
        if (output.isTimeout) {
            EnvResult.failedToStart("${command.describe(exe.toString())} timed out")
        } else {
            EnvResult(output.exitCode, output.stdout, output.stderr)
        }
    } catch (e: Exception) {
        LOG.warn("Failed to run ${command.describe(exe.toString())}", e)
        EnvResult.failedToStart(e.message ?: e.javaClass.simpleName)
    }

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
        val out = StringBuilder()
        val err = StringBuilder()
        return try {
            val handler = OSProcessHandler(cmd)
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val isError = outputType == ProcessOutputTypes.STDERR
                    (if (isError) err else out).append(event.text)
                    // Trimmed because the log adds its own newline, and a blank trailing line per
                    // chunk would double-space everything uv prints.
                    event.text.trimEnd('\n', '\r').takeIf { it.isNotEmpty() }?.let {
                        log.serverOutput(exe.fileName.toString(), it, isError)
                        // Progress is read from the same lines the log shows, so what the user sees
                        // spinning and what the log says can never disagree.
                        onLine(it)
                    }
                }
            })
            handler.startNotify()
            // Bounded so a tool waiting on a credential prompt we cannot see does not hold the
            // background task — and the operator's own progress indicator — open forever.
            if (!handler.waitFor(TimeUnit.MINUTES.toMillis(OPERATION_TIMEOUT_MINUTES))) {
                handler.destroyProcess()
                return EnvResult.failedToStart("${command.describe(exe.toString())} timed out")
            }
            EnvResult(handler.exitCode ?: EnvResult.NOT_STARTED, out.toString(), err.toString())
        } catch (e: Exception) {
            LOG.warn("Failed to run ${command.describe(exe.toString())}", e)
            EnvResult.failedToStart(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * How long a mutating operation gets.
     *
     * Generous, because the operations behind it legitimately are: `uv python install` downloads and
     * unpacks a CPython build, and a cold `uv sync` on a large project resolves and downloads
     * hundreds of wheels. This is a hang guard, not a performance budget.
     */
    private const val OPERATION_TIMEOUT_MINUTES = 15L
}
