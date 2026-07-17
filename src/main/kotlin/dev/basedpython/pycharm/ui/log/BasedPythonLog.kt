package dev.basedpython.pycharm.ui.log

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Project-level log sink for the basedpython plugin and its LSP servers.
 *
 * Lines are timestamped, appended to the "basedpython" tool window console
 * (created lazily, see [attachConsole]), and mirrored to [Logger]. Lines emitted
 * before the console exists are buffered and flushed on attachment.
 *
 * Auto-registers via `@Service(PROJECT)`; obtain with [getInstance].
 */
@Service(Service.Level.PROJECT)
internal class BasedPythonLog(private val project: Project) {

    private val log = Logger.getInstance(BasedPythonLog::class.java)
    private val lock = Any()

    /** Console set once the tool window is opened; null beforehand. */
    private var console: ConsoleView? = null

    /** Lines emitted before the console exists, flushed on [attachConsole]. */
    private val pending = ArrayDeque<Pair<String, ConsoleViewContentType>>()

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

    /** Lazily build (or reuse) the console backing the tool window. */
    fun getOrCreateConsole(): ConsoleView {
        synchronized(lock) {
            console?.let { return it }
            val view = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .console
            attachConsole(view)
            return view
        }
    }

    private fun attachConsole(view: ConsoleView) {
        synchronized(lock) {
            console = view
            while (pending.isNotEmpty()) {
                val (line, type) = pending.removeFirst()
                view.print(line, type)
            }
        }
    }

    private fun append(line: String, type: ConsoleViewContentType) {
        synchronized(lock) {
            val view = console
            if (view == null) {
                pending.addLast(line to type)
            } else {
                view.print(line, type)
            }
        }
    }

    private fun format(level: String, msg: String): String {
        val ts = LocalTime.now().format(TIME_FORMAT)
        return "$ts [$level] $msg\n"
    }

    companion object {
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun getInstance(project: Project): BasedPythonLog = project.service()
    }
}
