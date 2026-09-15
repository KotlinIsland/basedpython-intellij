package dev.basedpython.pycharm.ui.log

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Project-level log sink for the basedpython plugin and its LSP servers.
 *
 * Lines are timestamped, appended to the "basedpython" tool window console
 * (created lazily, see [getOrCreateConsole]), and mirrored to [Logger]. Lines emitted
 * before the console exists are held in a [PendingLines] of bounded size and flushed on
 * attachment — both servers write every stderr line here, and a tool window that is never opened
 * must not turn that into memory that only grows.
 *
 * Auto-registers via `@Service(PROJECT)`; obtain with [getInstance]. The console is disposed with
 * this service.
 */
@Service(Service.Level.PROJECT)
internal class BasedPythonLog(private val project: Project) : Disposable {

    private val log = Logger.getInstance(BasedPythonLog::class.java)
    private val lock = Any()

    /** Console set once the tool window is opened; null beforehand. */
    private var console: ConsoleView? = null

    /** Lines emitted before the console exists, flushed when it is created. */
    private val pending = PendingLines(MAX_PENDING_LINES)

    fun info(msg: String) {
        log.info(msg)
        append(format("INFO", msg), ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun warn(msg: String) {
        log.warn(msg)
        append(format("WARN", msg), ConsoleViewContentType.LOG_WARNING_OUTPUT)
    }

    fun error(msg: String) {
        log.error(msg)
        append(format("ERROR", msg), ConsoleViewContentType.ERROR_OUTPUT)
    }

    /**
     * Append a line a language server wrote to its own stderr.
     *
     * Deliberately not routed through [error]: that calls [Logger.error], which raises an IDE
     * fatal-error report. A server complaining about the user's code — or even panicking — is not
     * an IDE error, and reporting each one as such would bury the user in dialogs. It is still
     * coloured as error output so it stands out in the console, and mirrored to idea.log at info
     * level.
     *
     * [text] already carries the server's own timestamp and level, so it is passed through
     * verbatim rather than re-formatted.
     */
    fun serverOutput(serverName: String, text: String, isError: Boolean) {
        log.info("[$serverName] $text")
        val type =
            if (isError) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT
        append("[$serverName] $text\n", type)
    }

    /** Lazily build (or reuse) the console backing the tool window. */
    fun getOrCreateConsole(): ConsoleView {
        synchronized(lock) {
            console?.let { return it }
            val view = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
            Disposer.register(this, view)
            console = view
            val (dropped, lines) = pending.drain()
            if (dropped > 0) {
                view.print(
                    format("INFO", "$dropped earlier line(s) were dropped before this window was opened"),
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
            }
            for ((line, type) in lines) view.print(line, type)
            return view
        }
    }

    private fun append(line: String, type: ConsoleViewContentType) {
        synchronized(lock) {
            val view = console
            if (view == null) {
                pending.add(line, type)
            } else {
                view.print(line, type)
            }
        }
    }

    private fun format(level: String, msg: String): String {
        val ts = LocalTime.now().format(TIME_FORMAT)
        return "$ts [$level] $msg\n"
    }

    override fun dispose() {
        synchronized(lock) {
            console = null
            pending.drain()
        }
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        /**
         * How many lines are kept for a tool window that has not been opened yet: the most recent,
         * which is what someone opening it to see why a server misbehaved is looking for.
         */
        const val MAX_PENDING_LINES: Int = 2_000

        fun getInstance(project: Project): BasedPythonLog = project.service()
    }
}

/**
 * The last [capacity] lines, and a count of the ones pushed out to make room for them.
 *
 * Not thread-safe; [BasedPythonLog] guards it.
 */
internal class PendingLines(private val capacity: Int) {

    private val lines = ArrayDeque<Pair<String, ConsoleViewContentType>>()
    private var dropped = 0

    fun add(line: String, type: ConsoleViewContentType) {
        if (lines.size == capacity) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(line to type)
    }

    /** Everything held, oldest first, with how many were dropped; the buffer is empty afterwards. */
    fun drain(): Pair<Int, List<Pair<String, ConsoleViewContentType>>> {
        val result = dropped to lines.toList()
        lines.clear()
        dropped = 0
        return result
    }
}
