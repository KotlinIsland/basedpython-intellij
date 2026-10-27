package dev.basedpython.pycharm.console

import com.intellij.execution.console.LanguageConsoleBuilder
import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.process.OSProcessUtil
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.run.ByCommonOptions
import dev.basedpython.pycharm.run.byCommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private val LOG = Logger.getInstance(ByConsoleSession::class.java)

/**
 * One basedpython console: a `by repl` process talked to through its standard streams, and the
 * language console it is typed into.
 *
 * `by repl` runs in the project as `by run` would, with `--session-file` keeping the session's source
 * for completion ([ByConsoleDocument]). Each input is written to its standard input framed as a paste
 * ([ByConsoleText.framed]), so it is one input whatever it holds; what the program prints, the values
 * the session shows and its tracebacks are the process's output. Enter is [ByConsoleExecuteHandler]'s.
 *
 * Interrupting sends `SIGINT` to the process tree, as a terminal sends it to the process group: `by
 * repl` ignores it, and the interpreter running the input raises `KeyboardInterrupt` and carries on.
 *
 * Every input `by repl` reads is numbered, run or refused, and its tracebacks and diagnostics name it
 * `<by-input-N>`; the console keeps where each one is in its history, so those names link there.
 */
internal class ByConsoleSession private constructor(
    val project: Project,
    private val handler: KillableColoredProcessHandler,
    private val sessionFile: Path,
    id: Int,
) : Disposable {

    val document = ByConsoleDocument("untitled:basedpython-console-$id.by")

    /** Writes to `by repl`'s standard input one at a time and in order, never on the EDT: a pipe can be full. */
    private val input = AppExecutorUtil.createBoundedApplicationPoolExecutor("basedpython console input", 1)

    /** Where each input numbered so far is in the history, by its number. */
    private val inputs = java.util.concurrent.ConcurrentHashMap<Int, SentInput>()
    private val numbered = AtomicInteger()

    val executeHandler = ByConsoleExecuteHandler(this)

    val console: LanguageConsoleView = LanguageConsoleBuilder()
        .processHandler(handler)
        .initActions(executeHandler, HISTORY_TYPE)
        .build(project, BasedPythonLanguage)
        .also { console ->
            console.prompt = PROMPT
            console.addMessageFilter(ByConsoleInputFilter(this))
            ByConsoleExecuteHandler.registerRunNow(console, executeHandler)
            console.attachToProcess(handler)
        }

    /** The console's input file, which completion is asked from. */
    val inputFile: VirtualFile get() = console.virtualFile

    val isRunning: Boolean get() = !handler.isProcessTerminated

    init {
        Disposer.register(this, console)
        handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                document.close()
            }
        })
        handler.startNotify()
    }

    /** The session's source as `by repl` keeps it: the inputs that ran, each ending in a newline. */
    fun sessionSource(): String =
        try {
            Files.readString(sessionFile)
        } catch (e: java.io.IOException) {
            LOG.debug("could not read the session file", e)
            ""
        }

    /**
     * Runs [text] as though it had been typed and run with Ctrl+Enter — for an editor sending a line or
     * a selection — and puts back what was being typed. On the EDT.
     *
     * Through the input rather than printed into the history as the console's *user input*: a console
     * attached to a process writes what is printed as user input to the process too, so the input
     * reached `by repl` twice, once unframed.
     */
    fun execute(text: String) {
        val editor = console.consoleEditor
        val typed = editor.document.text
        val caret = editor.caretModel.offset
        console.setInputText(text)
        executeHandler.runNow(console)
        console.setInputText(typed)
        editor.caretModel.moveToOffset(caret.coerceAtMost(editor.document.textLength))
    }

    /**
     * Sends [text], just printed to the history, to `by repl` as one input, and remembers where it is
     * in the history under the number `by repl` gives it. On the EDT.
     */
    fun send(text: String) {
        if (!ByConsoleText.isInput(text)) return
        val number = numbered.incrementAndGet()
        remember(number, text)
        write(ByConsoleText.framed(text))
    }

    private fun write(text: String) {
        if (handler.isProcessTerminated) return
        val stream = handler.processInput
        input.execute {
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: java.io.IOException) {
                LOG.info("could not write to by repl", e)
            }
        }
    }

    /**
     * Records where input [number], which ends the history, is: the history's last lines, each the
     * prompt the console printed before it and then the input's own line.
     */
    private fun remember(number: Int, text: String) {
        val history = (console as? LanguageConsoleImpl)?.let { console ->
            // the input is still in the console's buffer; it is printed before anything printed after it
            console.flushDeferredText()
            console.historyViewer.document
        } ?: return
        // one line of the history each, a last empty one included: the console prints the input and then a line break
        val lines = text.split('\n')
        val end = history.textLength
        if (end == 0 || history.charsSequence[end - 1] != '\n') return
        val first = history.getLineNumber(end) - lines.size
        if (first < 0) return
        val prefixes = lines.mapIndexed { index, line ->
            val historyLine = first + index
            (history.getLineEndOffset(historyLine) - history.getLineStartOffset(historyLine) - line.length).coerceAtLeast(0)
        }
        val marker = history.createRangeMarker(history.getLineStartOffset(first), history.getLineEndOffset(first + lines.size - 1))
        inputs[number] = SentInput(marker, prefixes)
    }

    /** Where `<by-input-[number]>`'s [line] and [column], 1-based, are in the history, if it is still there. */
    fun historyOffset(number: Int, line: Int, column: Int): Int? {
        val sent = inputs[number] ?: return null
        if (!sent.marker.isValid) return null
        val history = sent.marker.document
        val index = (line - 1).coerceIn(0, sent.prefixes.size - 1)
        val historyLine = history.getLineNumber(sent.marker.startOffset) + index
        if (historyLine >= history.lineCount) return null
        val start = history.getLineStartOffset(historyLine) + sent.prefixes[index]
        return (start + column - 1).coerceAtMost(history.getLineEndOffset(historyLine))
    }

    /** Links to [offset] in the history. */
    fun historyLink(offset: Int): HyperlinkInfo = HyperlinkInfo {
        val viewer = (console as? LanguageConsoleImpl)?.historyViewer ?: return@HyperlinkInfo
        if (offset > viewer.document.textLength) return@HyperlinkInfo
        viewer.caretModel.moveToOffset(offset)
        viewer.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
    }

    /** Whether [interrupt] can reach the input running: `SIGINT` is a Unix signal. */
    val canInterrupt: Boolean get() = SystemInfo.isUnix && isRunning

    /** Interrupts the input running with a `KeyboardInterrupt`; the session carries on. */
    fun interrupt() {
        if (!canInterrupt) return
        // every process of the tree, as a terminal signals the whole process group: `by repl` itself
        // ignores it, and the interpreter it runs the session in is its child
        val root = handler.process.toHandle()
        for (process in sequenceOf(root) + root.descendants().iterator().asSequence()) {
            // SIGINT, on Unix
            OSProcessUtil.terminateProcessGracefully(process.pid().toInt())
        }
    }

    /** Ends the session: its process tree, and what it was holding. */
    fun stop() {
        handler.destroyProcess()
    }

    /** Prints a line of the console's own, not the program's. */
    fun note(text: String) {
        console.print(text + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    /** The project's running `by`, or null; never starts one. */
    fun runningBy(): LspClient? =
        LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    override fun dispose() {
        if (!handler.isProcessTerminated) handler.destroyProcess()
        document.close()
        input.shutdown()
        try {
            Files.deleteIfExists(sessionFile)
        } catch (e: java.io.IOException) {
            LOG.debug("could not delete the session file", e)
        }
    }

    private class SentInput(val marker: RangeMarker, val prefixes: List<Int>)

    companion object {
        const val PROMPT = ">>> "

        /** The console history's own kind, which the platform keeps the history under. */
        private const val HISTORY_TYPE = "basedpython"

        private val ids = AtomicInteger()

        /**
         * Starts `by repl` for [project]. Starts a process, so off the EDT; the console it returns is
         * built on the EDT by [build].
         *
         * @throws com.intellij.execution.ExecutionException when no `by` can be found or started.
         */
        fun startProcess(project: Project): Pair<KillableColoredProcessHandler, Path> {
            val sessionFile = Files.createTempFile("basedpython-console", ".by")
            val commandLine = byCommandLine(
                project = project,
                options = ByCommonOptions(),
                arguments = listOf("repl", "--session-file", sessionFile.toString()),
                // the session imports the project's modules as a program does
                startsProgram = true,
            )
            val handler = KillableColoredProcessHandler(commandLine)
            // `by repl` ignores SIGINT, so the soft kill would interrupt the input running rather than
            // stop anything; and the interpreter is its child, which a stop has to reach too
            handler.setShouldKillProcessSoftly(false)
            handler.setShouldDestroyProcessRecursively(true)
            ProcessTerminatedListener.attach(handler)
            return handler to sessionFile
        }

        /** The console for a process [startProcess] started. On the EDT. */
        fun build(project: Project, process: Pair<KillableColoredProcessHandler, Path>): ByConsoleSession =
            ByConsoleSession(project, process.first, process.second, ids.incrementAndGet())
    }
}
