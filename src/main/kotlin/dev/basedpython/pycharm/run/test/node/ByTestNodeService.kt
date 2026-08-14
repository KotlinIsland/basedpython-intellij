package dev.basedpython.pycharm.run.test.node

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.ui.log.BasedPythonLog
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Paths
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the collected test tree for one project and refreshes it by running
 * `by run pytest --collect-only -q`.
 *
 * The collection is a real `by run`, so it transpiles the project before pytest sees it — it costs
 * what a test run costs minus the tests, and is never started on its own. It happens when the tool
 * window is first opened and whenever the user asks for it, and not, deliberately, on every edit
 * or save: a file watcher would transpile the world each time a character lands in a `.by` file.
 */
@Service(Service.Level.PROJECT)
internal class ByTestNodeService(private val project: Project) {

    /** What the view has to show right now. */
    sealed interface State {
        /** Nothing collected yet. */
        data object Idle : State

        /** A collection is running; the previous [tree] (if any) stays on screen meanwhile. */
        data class Collecting(val tree: ByTestNode?) : State

        /** The result of the last collection, errors included as nodes. */
        data class Collected(val tree: ByTestNode, val index: ByTestIndex) : State
    }

    /**
     * The last collection, as the gutter markers ask about it. [ByTestIndex.EMPTY] until something
     * has been collected — which reads as "nothing is known", not as "there are no tests".
     */
    val index: ByTestIndex
        get() = (state as? State.Collected)?.index ?: ByTestIndex.EMPTY

    @Volatile
    var state: State = State.Idle
        private set

    /**
     * What the last collection ran and printed, verbatim, for *View Collection Output* — one entry
     * per half, since collection is a `by run pytest` and a plain `pytest` combined.
     *
     * The tree is a summary and cannot answer "why does this disagree with the pytest I ran
     * myself"; this can. Kept even when the collection succeeded, since that question is asked
     * about successful-looking runs most of all.
     */
    @Volatile
    var lastRuns: List<ByCollectionRun> = emptyList()
        private set

    /** Guards against a second collection while one is in flight. */
    private val running = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Registers [listener], called on the EDT after every [state] change, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Collects unless something already has.
     *
     * The trigger behind opening the tool window and behind the first gutter icon that needs an
     * answer ([ByTestLookup]). Only ever fires once: a collection that failed leaves a
     * [State.Collected] holding the error, which is a result and not a reason to try again on the
     * next keystroke.
     */
    fun refreshIfNeeded() {
        if (state == State.Idle) refresh()
    }

    /** Re-runs collection, unless one is already in flight. */
    fun refresh() {
        if (!running.compareAndSet(false, true)) return
        setState(State.Collecting(state.shownTree))
        // Scheduled rather than started here: this is called from the daemon's threads as well as
        // from the tool window's, and starting a Backgroundable task is the EDT's job.
        ApplicationManager.getApplication().invokeLater({ start() }, ModalityState.any(), project.disposed)
    }

    private fun start() {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, BasedPythonBundle.message("testNodes.progress"), true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val startedAt = System.currentTimeMillis()
                    setState(collected(collect(), startedAt))
                }

                override fun onThrowable(error: Throwable) {
                    BasedPythonLog.getInstance(project).warn("test collection failed: $error")
                    setState(collected(failure(error.message), System.currentTimeMillis()))
                }

                override fun onFinished() {
                    running.set(false)
                }
            },
        )
    }

    /** One `by run pytest --collect-only -q`, as a [ByCollection]. */
    private fun collect(): ByCollection {
        val cwd = project.basePath?.let { Paths.get(it) }
        val command = ByPytestCollect.arguments().toTypedArray()
        val startedAt = System.currentTimeMillis()
        val startedAtDisplay = LocalTime.now().format(STARTED_AT_FORMAT)
        val execution = ByCli.runDetailed(project, SUBCOMMAND, *command, cwd = cwd, timeoutMs = TIMEOUT_MS)
        if (execution == null) {
            val message = BasedPythonBundle.message("testNodes.error.binaryMissing")
            lastRuns = listOf(ByCollectionRun(
                label = BY_RUN_LABEL,
                commandLine = "by $SUBCOMMAND ${command.joinToString(" ")}",
                workingDirectory = cwd?.toString(),
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMillis = System.currentTimeMillis() - startedAt,
                startedAt = startedAtDisplay,
                failure = message,
            ))
            return failure(message)
        }

        val output = execution.output
        lastRuns = listOf(ByCollectionRun(
            label = BY_RUN_LABEL,
            commandLine = execution.commandLine,
            workingDirectory = execution.workingDirectory,
            exitCode = output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
            durationMillis = System.currentTimeMillis() - startedAt,
            startedAt = startedAtDisplay,
        ))

        if (output.isTimeout) {
            return failure(BasedPythonBundle.message("testNodes.error.timeout", TIMEOUT_MS / 1000))
        }

        val collection = ByPytestCollect.parse(output.stdout, output.stderr, output.exitCode) +
            collectPythonTests(cwd)
        if (collection.errors.isNotEmpty()) {
            // The tree only has room for one line per error; the whole report is worth keeping.
            BasedPythonLog.getInstance(project).warn(
                "test collection reported ${collection.errors.size} error(s):\n" +
                    (output.stderr.ifBlank { output.stdout }).trim(),
            )
        }
        return collection
    }

    /**
     * The other half of the collection: `python -m pytest --collect-only` in the project itself.
     *
     * `by run` transpiles only `.by` files into the tree it hands pytest, so a project whose tests
     * live in `.py` files hands it an empty one — the exact case where the view says "no tests" and
     * the same `pytest --collect-only` typed in a terminal lists them. Until `by run` can be told
     * to include them, they are collected here and combined.
     *
     * Best-effort by design. A `.by`-only project need not have pytest importable by the
     * interpreter *this* half resolves, and that is not a failure of anything: it is reported as
     * nothing to add rather than as a red node under every such project. Skipped entirely when no
     * `.py` test file exists, which keeps a second `by`-less process off the common path.
     */
    private fun collectPythonTests(cwd: java.nio.file.Path?): ByCollection {
        if (cwd == null || ByPythonTests.find(cwd, limit = 1).isEmpty()) return ByCollection()
        val python = ByEnvironments.resolvePython(project) ?: return ByCollection()
        val startedAt = System.currentTimeMillis()
        val command = GeneralCommandLine()
            .withExePath(python.exe.toString())
            .withParameters(python.prependArgs)
            .withParameters(ByPytestCollect.pythonArguments())
            .withCharset(Charsets.UTF_8)
            .withEnvironment(python.env)
            .withWorkDirectory(cwd.toFile())
        val output = try {
            ExecUtil.execAndGetOutput(command, TIMEOUT_MS)
        } catch (e: ExecutionException) {
            BasedPythonLog.getInstance(project).warn("plain pytest collection could not start: $e")
            return ByCollection()
        }
        lastRuns += ByCollectionRun(
            label = PLAIN_PYTEST_LABEL,
            commandLine = command.commandLineString,
            workingDirectory = cwd.toString(),
            exitCode = output.exitCode,
            stdout = output.stdout,
            stderr = output.stderr,
            durationMillis = System.currentTimeMillis() - startedAt,
            startedAt = LocalTime.now().format(STARTED_AT_FORMAT),
        )
        if (output.isTimeout || ByPytestCollect.isPytestMissing(output.stderr + output.stdout)) {
            return ByCollection()
        }
        return ByPytestCollect.parse(output.stdout, output.stderr, output.exitCode, ByTestSource.PYTHON)
    }

    /**
     * @param startedAtMillis when the collection was launched, not when it finished: a file written
     *   while `by` was running may not be in the result, and has to count as newer than it.
     */
    private fun collected(collection: ByCollection, startedAtMillis: Long): State.Collected =
        State.Collected(
            tree = ByTestNodes.build(collection, rootName()),
            index = ByTestIndex.of(collection, startedAtMillis),
        )

    private fun failure(message: String?): ByCollection = ByCollection(
        errors = listOf(
            ByCollectionError(null, message?.takeIf { it.isNotBlank() } ?: UNKNOWN_FAILURE),
        ),
    )

    private fun rootName(): String = BasedPythonBundle.message("testNodes.root")

    private fun setState(next: State) {
        state = next
        ApplicationManager.getApplication().invokeLater(
            {
                listeners.forEach { it() }
                // The gutter icons in open editors are built from [index]
                // ([dev.basedpython.pycharm.run.testmarker.ByTestRunLineMarkerContributor]), and
                // nothing else tells the daemon that the answer just changed — without this they
                // would keep the previous collection's verdict until the file is edited.
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).restart(RESTART_REASON)
                }
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    /** The tree a state is showing, if it has one. */
    private val State.shownTree: ByTestNode?
        get() = when (this) {
            is State.Collected -> tree
            is State.Collecting -> tree
            State.Idle -> null
        }

    companion object {
        fun getInstance(project: Project): ByTestNodeService = project.service()

        private const val SUBCOMMAND = "run"

        /**
         * Collection is not supposed to run any test, but importing a module runs whatever sits at
         * its top level, so a project can hang this on `input()` or a socket that never connects.
         * Two minutes is far past a real transpile-and-collect and still ends.
         */
        private const val TIMEOUT_MS = 120_000

        private const val UNKNOWN_FAILURE = "collection failed"

        /** How the two halves of a collection are named in *View Collection Output*. */
        private const val BY_RUN_LABEL = "by run pytest (tests transpiled from .by)"
        private const val PLAIN_PYTEST_LABEL = "plain pytest (tests already in .py)"

        /** Clock time in the output header; seconds are as precise as this needs to be. */
        private val STARTED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Why the daemon was restarted, for the platform's own logging of who asked. */
        private const val RESTART_REASON = "basedpython test collection finished"
    }
}
