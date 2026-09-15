package dev.basedpython.pycharm.run.test.node

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByProjectTests
import dev.basedpython.pycharm.run.model.ByTestItem
import dev.basedpython.pycharm.run.runCapturing
import dev.basedpython.pycharm.run.test.tree.ByTestSources
import dev.basedpython.pycharm.ui.log.BasedPythonLog
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds


/**
 * Holds the test tree for one project.
 *
 * ## Where the tree comes from
 *
 * By default, from `by/testItems` ([ByProgramModel.projectTests]): the checker's static model of
 * which tests pytest would collect. It is refreshed whenever the program model is, which is a moment
 * after the project's files change and whenever the server starts — and it runs nothing.
 *
 * `by run pytest --collect-only` is the other source, and it is only ever run when the user asks
 * for it ([refresh]). pytest *imports* every test module to collect it, so collecting executes the
 * project's code — its `conftest.by`, every test module's top level. That is a reasonable thing to
 * do on request, to see what pytest itself makes of the project (parametrized cases, collection
 * errors), and not a thing to do on a user's behalf when a project opens or a file is saved. Its
 * result stands until the static model next changes.
 */
@Service(Service.Level.PROJECT)
internal class ByTestNodeService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    /** Nothing to release: this exists so listeners can be tied to the service's own lifetime. */
    override fun dispose() = Unit

    /** What the view has to show right now. */
    sealed interface State {
        /** Nothing known yet: the server has not answered, or is not running. */
        data object Idle : State

        /** A pytest collection is running; the previous [tree] (if any) stays on screen meanwhile. */
        data class Collecting(val tree: ByTestNode?) : State

        /**
         * A tree to show: the server's static answer, or — when [fromPytest] — what a collection the
         * user asked for reported, errors included as nodes.
         */
        data class Collected(val tree: ByTestNode, val fromPytest: Boolean) : State
    }

    @Volatile
    var state: State = State.Idle
        private set

    /**
     * What the last pytest collection ran and printed, verbatim, for *View Collection Output* — one
     * entry per half, since collection is a `by run pytest` and a plain `pytest` combined.
     */
    @Volatile
    var lastRuns: List<ByCollectionRun> = emptyList()
        private set

    /** Guards against a second collection while one is in flight. */
    private val collecting = AtomicBoolean(false)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * What the last run said about each test, keyed by the pytest node id the tree stores as a
     * node's target.
     *
     * Outcomes outlive the run that produced them and are only replaced per test, so running one
     * test leaves every other result standing. A new tree does not clear them either: rediscovering
     * the same tests is no reason to forget how they did, and anything that has genuinely gone stops
     * being looked up the moment it leaves the tree.
     */
    @Volatile
    var outcomes: Map<String, ByTestState> = emptyMap()
        private set

    private val outcomeListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        project.messageBus.connect(this).subscribe(ByProgramModel.CHANGED, Runnable { showStatic() })
    }

    /** Registers [listener], called on the EDT after every [state] change, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /**
     * Registers [listener], called on the EDT after every [outcomes] change.
     *
     * Separate from [addListener] because the two mean different things to a view: a new tree
     * changes which nodes exist and costs a rebuild, while an outcome changes only what a row looks
     * like — and a run reports one of those per test, far too often to rebuild a tree for.
     */
    fun addOutcomeListener(parent: Disposable, listener: () -> Unit) {
        outcomeListeners += listener
        Disposer.register(parent) { outcomeListeners -= listener }
    }

    /**
     * Marks every test under [target] (null meaning all of [source]) as running.
     *
     * Called when a run is launched rather than when a test reports, because pytest's `-v` output
     * gives no earlier moment: it prints the node id and the outcome on one line, so a parsed run
     * only learns a test existed once it is over. What *is* known at launch is the scope that was
     * asked for, and showing that is the difference between a view that reacts and one that sits
     * still until the whole suite is done.
     *
     * Outcomes overwrite these as they arrive, and [clearRunning] sweeps up whatever never reported.
     */
    fun markRunning(target: String?, source: ByTestSource) {
        val tree = (state as? State.Collected)?.tree ?: return
        val running = HashMap(outcomes)
        forEachTest(tree) { node ->
            if (node.source == source && node.target != null && covers(target, node.target)) {
                running[node.target] = ByTestState.RUNNING
            }
        }
        if (running == outcomes) return
        outcomes = running
        fireOutcomes()
    }

    /** True when a run of [target] includes [nodeId]; a null target is the whole project. */
    private fun covers(target: String?, nodeId: String): Boolean = when {
        target == null -> true
        nodeId == target -> true
        // The boundaries matter: `tests/test_a.py` must not swallow `tests/test_ab.py`, while
        // `…::test_param` has to take its `[1-2]` cases with it.
        else -> nodeId.startsWith("$target::") || nodeId.startsWith("$target/") ||
            nodeId.startsWith("$target[")
    }

    private fun forEachTest(node: ByTestNode, action: (ByTestNode) -> Unit) {
        if (node.children.isEmpty()) action(node) else node.children.forEach { forEachTest(it, action) }
    }

    /** Records [state] for the test [nodeId], as reported by a run. */
    fun setOutcome(nodeId: String, state: ByTestState) {
        outcomes = outcomes + (nodeId to state)
        fireOutcomes()
    }

    /**
     * Clears anything still [ByTestState.RUNNING], for when a run ends without reporting them.
     *
     * A stopped run, a crashed interpreter, a test that killed the process: the tests it had
     * started would otherwise spin in the view until the next collection.
     */
    fun clearRunning() {
        val stuck = outcomes.filterValues { it == ByTestState.RUNNING }
        if (stuck.isEmpty()) return
        outcomes = outcomes - stuck.keys
        fireOutcomes()
    }

    private fun fireOutcomes() {
        ApplicationManager.getApplication().invokeLater(
            { outcomeListeners.forEach { it() } },
            ModalityState.any(),
            project.disposed,
        )
    }

    /**
     * Shows the server's static answer, when there is one; asks for one when there is not.
     *
     * Not while a pytest collection the user asked for is running: its answer is the one they are
     * waiting for.
     */
    fun showStatic() {
        if (collecting.get()) return
        val tests = model.projectTests() ?: return
        setState(State.Collected(buildTree(staticCollection(tests)), fromPytest = false))
    }

    /** The server's answer, as the node ids pytest would report for it. */
    private fun staticCollection(tests: ByProjectTests): ByCollection = ByCollection(
        nodes = tests.byFile.flatMap { (file, items) ->
            if (!file.isValid) return@flatMap emptyList()
            val transpiled = file.extension == BY_EXTENSION
            // A `.by` is collected where `by run` stages it, which is how every node id the tree and
            // a run speak in names it; a `.py` is collected by plain pytest in the project, as itself.
            val nodePath = (if (transpiled) model.stagedPath(file) else ByTestSources.relativePath(project, file))
                ?: return@flatMap emptyList()
            val source = if (transpiled) ByTestSource.TRANSPILED else ByTestSource.PYTHON
            items.flatMap(::leaves).map { ByCollectedNode("$nodePath::${it.symbols.joinToString("::")}", source) }
        },
    )

    private fun leaves(item: ByTestItem): List<ByTestItem> =
        if (item.isClass) item.children.flatMap(::leaves) else listOf(item)

    /**
     * Runs pytest's own collection, which the user asked for; false when one is already running.
     *
     * Cancellable for real: the processes belong to a coroutine under a cancellable progress, and
     * cancelling it — or closing the project — destroys them, rather than leaving the Cancel button
     * to wait out whatever an imported test module is doing.
     */
    fun refresh(): Boolean {
        if (!collecting.compareAndSet(false, true)) return false
        setState(State.Collecting(state.shownTree))
        scope.launch {
            try {
                val collection = withBackgroundProgress(
                    project,
                    BasedPythonBundle.message("testNodes.progress"),
                    cancellable = true,
                ) { collect() }
                setState(collected(collection))
            } catch (e: CancellationException) {
                // Put back what was there before, rather than a spinner nothing will stop.
                collecting.set(false)
                val previous = state.shownTree
                showStatic()
                if (state is State.Collecting) {
                    setState(previous?.let { State.Collected(it, fromPytest = false) } ?: State.Idle)
                }
                throw e
            } catch (e: Exception) {
                BasedPythonLog.getInstance(project).warn("test collection failed: $e")
                setState(collected(failure(e.message)))
            } finally {
                collecting.set(false)
            }
        }
        return true
    }

    /** One `by run pytest --collect-only -q`, and the plain-pytest half, as a [ByCollection]. */
    private suspend fun collect(): ByCollection {
        val cwd = project.basePath?.let { Paths.get(it) }
        val arguments = ByPytestCollect.arguments()
        val startedAtDisplay = LocalTime.now().format(STARTED_AT_FORMAT)
        val command = ByCli.commandLine(project, SUBCOMMAND, *arguments.toTypedArray(), cwd = cwd)
        if (command == null) {
            val message = BasedPythonBundle.message("testNodes.error.binaryMissing")
            lastRuns = listOf(ByCollectionRun(
                label = BY_RUN_LABEL,
                commandLine = "by $SUBCOMMAND ${arguments.joinToString(" ")}",
                workingDirectory = cwd?.toString(),
                exitCode = -1,
                stdout = "",
                stderr = "",
                durationMillis = 0,
                startedAt = startedAtDisplay,
                failure = message,
            ))
            return failure(message)
        }

        val (output, run) = runRecorded(BY_RUN_LABEL, command, cwd, startedAtDisplay)
        lastRuns = listOf(run)
        if (output == null) {
            return failure(BasedPythonBundle.message("testNodes.error.timeout", TIMEOUT.inWholeSeconds))
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
     * live in `.py` files hands it an empty one. Until `by run` can be told to include them, they are
     * collected here and combined.
     *
     * Best-effort by design. A `.by`-only project need not have pytest importable by the interpreter
     * *this* half resolves, and that is reported as nothing to add rather than as a red node under
     * every such project. Skipped entirely when no `.py` test file exists.
     */
    private suspend fun collectPythonTests(cwd: Path?): ByCollection {        if (cwd == null || ByPythonTests.find(cwd, limit = 1).isEmpty()) return ByCollection()
        val python = ByEnvironments.resolvePython(project) ?: return ByCollection()
        val command = GeneralCommandLine()
            .withExePath(python.exe.toString())
            .withParameters(python.prependArgs)
            .withParameters(ByPytestCollect.pythonArguments(buildDirectories(cwd)))
            .withCharset(Charsets.UTF_8)
            .withEnvironment(python.env)
            .withWorkDirectory(cwd.toFile())
        val (output, run) = runRecorded(PLAIN_PYTEST_LABEL, command, cwd, LocalTime.now().format(STARTED_AT_FORMAT))
        lastRuns += run
        if (output == null || ByPytestCollect.isPytestMissing(output.stderr + output.stdout)) {
            return ByCollection()
        }
        return ByPytestCollect.parse(output.stdout, output.stderr, output.exitCode, ByTestSource.PYTHON)
    }

    /**
     * Where `by build` writes this project, so the plain half does not collect it a second time.
     *
     * `by`'s answer for [cwd] when the server can give one now, and otherwise the ones it gave last
     * — see [ByBuildOutputs].
     */
    private fun buildDirectories(cwd: Path): List<String> {
        val outputs = ByBuildOutputs.getInstance(project)
        val root = LocalFileSystem.getInstance().findFileByNioFile(cwd)
        return root?.let { outputs.of(it)?.buildDirectory }?.let(::listOf) ?: outputs.buildDirectories
    }

    /**
     * Runs [command] until it exits or [TIMEOUT] passes, and records it for *View Collection Output*.
     * A null output is the timeout; the process has been destroyed by then.
     */
    private suspend fun runRecorded(
        label: String,
        command: GeneralCommandLine,
        cwd: Path?,
        startedAtDisplay: String,
    ): Pair<ProcessOutput?, ByCollectionRun> {
        val startedAt = System.currentTimeMillis()
        val output = withTimeoutOrNull(TIMEOUT) { runCapturing(command) }
        val run = ByCollectionRun(
            label = label,
            commandLine = command.commandLineString,
            workingDirectory = cwd?.toString(),
            exitCode = output?.exitCode ?: -1,
            stdout = output?.stdout.orEmpty(),
            stderr = output?.stderr.orEmpty(),
            durationMillis = System.currentTimeMillis() - startedAt,
            startedAt = startedAtDisplay,
            failure = if (output == null) BasedPythonBundle.message("testNodes.error.timeout", TIMEOUT.inWholeSeconds) else null,
        )
        return output to run
    }

    private fun collected(collection: ByCollection): State.Collected =
        State.Collected(tree = buildTree(collection), fromPytest = true)

    /** The tree for [collection], its transpiled files named as the sources `by` staged them from. */
    private fun buildTree(collection: ByCollection): ByTestNode =
        ByTestNodes.build(collection, rootName()) { staged -> model.stagedFile(staged)?.name }

    private val model: ByProgramModel get() = ByProgramModel.getInstance(project)

    private fun failure(message: String?): ByCollection = ByCollection(
        errors = listOf(
            ByCollectionError(null, message?.takeIf { it.isNotBlank() } ?: UNKNOWN_FAILURE),
        ),
    )

    private fun rootName(): String = BasedPythonBundle.message("testNodes.root")

    private fun setState(next: State) {
        state = next
        ApplicationManager.getApplication().invokeLater(
            { listeners.forEach { it() } },
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
        private const val BY_EXTENSION = "by"

        /**
         * Collection is not supposed to run any test, but importing a module runs whatever sits at
         * its top level, so a project can hang this on `input()` or a socket that never connects.
         * Two minutes is far past a real transpile-and-collect and still ends.
         */
        private val TIMEOUT = 120_000.milliseconds

        private const val UNKNOWN_FAILURE = "collection failed"

        /** How the two halves of a collection are named in *View Collection Output*. */
        private const val BY_RUN_LABEL = "by run pytest (tests transpiled from .by)"
        private const val PLAIN_PYTEST_LABEL = "plain pytest (tests already in .py)"

        /** Clock time in the output header; seconds are as precise as this needs to be. */
        private val STARTED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
