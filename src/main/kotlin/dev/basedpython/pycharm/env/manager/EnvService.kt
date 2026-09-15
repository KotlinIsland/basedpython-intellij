package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.basedpython.pycharm.env.modules.ModuleLayout
import dev.basedpython.pycharm.ui.log.BasedPythonLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the project's environment is, and the one place operations on it are run.
 *
 * ### What a refresh costs
 *
 * More than the task view's and less than the test view's, which is why it sits between them on
 * ceremony: it re-runs on project open and when a manifest changes (debounced), but never on a
 * timer. A full refresh is two `stat`s and a small text file for the environment itself, plus two
 * short-lived processes — a package list and a drift probe — and both of those are skipped entirely
 * when there is no environment to ask about. The expensive one is the drift probe, which resolves
 * against the lock file and can touch the network on a cold cache; it is the reason a refresh is
 * debounced rather than run on every keystroke in `pyproject.toml`.
 *
 * ### What it will not do
 *
 * Never creates or modifies an environment on its own. The plugin's long-standing rule is that uv
 * runs only when the user asked (see [dev.basedpython.pycharm.env.ByEnvironmentKind.UV]) — `uv sync`
 * writes a lock file and can download a CPython toolchain, which is the right thing on a button
 * press and an unacceptable side effect of opening a file. This service reports; [EnvOperations]
 * acts, and only from a user gesture.
 */
@Service(Service.Level.PROJECT)
internal class EnvService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    override fun dispose() = Unit

    @Volatile
    var status: EnvStatus = EnvStatus.unknown(basePath())
        private set

    /** Which scans run when — see [EnvRefreshQueue] for the rules. */
    private val refreshes = EnvRefreshQueue(::startScan)

    /**
     * True while a refresh or an operation is in flight, so the view can disable what must not run
     * twice.
     */
    val busy: Boolean get() = refreshes.busy

    /**
     * Marks an operation in flight for the duration of [block], on the calling thread, and scans
     * once it is over.
     *
     * How [EnvOperations] keeps a whole multi-step gesture — install, create, sync — reading as one
     * busy stretch rather than three, with no scan reading the environment halfway through it and
     * exactly one reading it afterwards, however the gesture ended.
     */
    fun <T> busyWhile(block: () -> T): T {
        refreshes.operationStarted()
        fire()
        return try {
            block()
        } finally {
            refreshes.operationFinished()
            fire()
        }
    }

    /** A listener, and the modality it is told in — see [addListener]. */
    private class Listener(val modality: ModalityState, val onChange: () -> Unit)

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Set by the first request for a scan, so [refreshIfNeeded] asks once rather than per caller. */
    private val everRequested = AtomicBoolean(false)

    /**
     * What each package is doing while an operation runs.
     *
     * Replaced wholesale rather than mutated, so the view — which paints on the EDT while the output
     * arrives on a process thread — always reads a coherent picture.
     */
    @Volatile
    var progress: EnvProgress = EnvProgress()
        private set

    /** Applies one line of a running operation's output to [progress]. */
    fun onOperationOutput(line: String) {
        val event = EnvProgressLine.parse(line) ?: return
        progress = progress.with(event)
        fire()
    }

    /** Marks [names] busy before the tool has said anything — see [EnvProgress.starting]. */
    fun markStarting(names: Collection<String>, what: EnvPackageActivity) {
        progress = progress.starting(names, what)
        fire()
    }

    /** Clears the progress, for when an operation ends however it ended. */
    fun clearProgress() {
        progress = progress.cleared()
        fire()
    }

    /** True once something has refreshed, so the view can tell "nothing here" from "not looked yet". */
    @Volatile
    var scanned: Boolean = false
        private set

    private var syncJob: Job? = null

    /**
     * Registers [listener], called on the EDT after every change, until [parent] is disposed.
     *
     * [modality] is when it may run. The default is the tool window's answer — never while a dialog
     * is up, see [fire]. A view that itself lives *inside* a modal dialog cannot take that default:
     * non-modal notifications wait for every dialog to close, the one it is drawn in included, so
     * *Settings | Modules* would show the table as it was when Settings opened until Settings closed.
     * Such a view passes [ModalityState.any] and decides for itself, against its own component,
     * whether now is a moment it can redraw.
     */
    fun addListener(parent: Disposable, modality: ModalityState = ModalityState.nonModal(), listener: () -> Unit) {
        val registered = Listener(modality, listener)
        listeners += registered
        Disposer.register(parent) { listeners -= registered }
    }

    /** Scans unless something already has, or already asked to. */
    fun refreshIfNeeded() {
        if (!everRequested.get()) refresh()
    }

    /**
     * Re-reads a short while after the manifests stop changing.
     *
     * Longer than the task view's 500ms because the drift probe is a process that resolves a
     * dependency graph, and someone typing a dependency name into `pyproject.toml` would otherwise
     * start one per pause. Short enough that saving the file and looking at the tool window shows
     * the new state without a Refresh.
     */
    fun scheduleRefresh() {
        // Stamped now, when the file changed, rather than when the delay is up: a scan that starts
        // in between — the one an operation ends with — has read this change already.
        val changedAt = refreshes.noteChange()
        synchronized(this) {
            syncJob?.cancel()
            syncJob = scope.launch {
                delay(REFRESH_DELAY_MILLIS)
                refreshes.requestIfStale(changedAt)
            }
        }
    }

    /**
     * Re-reads — now, or as soon as the scan or operation in flight has finished.
     *
     * Never dropped: see [EnvRefreshQueue].
     */
    fun refresh() {
        everRequested.set(true)
        refreshes.request()
    }

    /** One scan, on the IO dispatcher, reporting back to [refreshes] however it ends. */
    private fun startScan() {
        everRequested.set(true)
        fire()
        scope.launch(Dispatchers.IO) {
            try {
                setStatus(scan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Reading another tool's output and another tool's files; anything it throws is
                // about one project's configuration and must not take the service down.
                BasedPythonLog.getInstance(project).warn("environment scan failed: $e")
                setStatus(EnvStatus.unknown(basePath()).copy(error = e.message ?: e.toString()))
            } finally {
                scanned = true
                refreshes.scanFinished()
                fire()
            }
        }
    }

    /**
     * The interpreters the backend can offer, for the version picker.
     *
     * Not part of [status]: it is a process spawn whose answer nothing displays until a picker is
     * opened, and it is the same on every project. Callers run it off the EDT.
     */
    fun listPythons(): List<PythonCandidate> {
        val backend = status.backend ?: return emptyList()
        val root = status.projectRoot ?: return emptyList()
        val command = backend.command(EnvOp.ListPythons) ?: return emptyList()
        val result = EnvRunner.run(project, backend, command, root)
        return if (result.isSuccess) backend.parsePythons(result.stdout) else emptyList()
    }

    // ---- scanning ----------------------------------------------------------

    /**
     * The current state of the project's environment.
     *
     * Ordered so that each step's cost is only paid once the previous one justified it: no backend
     * means no tool lookup, no tool means no processes, and no environment on disk means neither the
     * package list nor the drift probe is run. A project that is not a Python project therefore
     * costs a handful of `stat` calls and nothing else.
     */
    private fun scan(): EnvStatus {
        val root = basePath() ?: return EnvStatus.unknown(null)
        val backend = EnvBackends.detect(root) ?: return EnvStatus.unknown(root)
        val tool = EnvTools.find(backend)
        val envRoot = backend.environmentRoot(root)
        val base = EnvStatus(
            projectRoot = root,
            backend = backend,
            toolPath = tool,
            environmentRoot = envRoot,
            environment = readEnvironment(backend, envRoot),
            drift = EnvDrift.UNKNOWN,
            packages = emptyList(),
            // Before the early return below, deliberately. A project's modules need the tool but not
            // the environment, and the state that most needs them read is the one that returns
            // early: a workspace whose environment has not been created yet is exactly when someone
            // opens the structure page to add the module they are about to sync.
            modules = tool?.let { readModules(backend, root) },
        )
        if (tool == null || base.environment == null) return base
        return base.copy(
            packages = readPackages(backend, root, base.environment),
            drift = readDrift(backend, root),
            graph = readDependencies(backend, root),
        )
    }

    /**
     * The environment on disk, or null when there is not one.
     *
     * Keyed on the interpreter being executable rather than on the directory existing. A `.venv`
     * whose interpreter is gone — the usual result of a Homebrew Python upgrade, or of copying a
     * project between machines — is not an environment, and reporting it as one produces the worst
     * version of this feature: a view claiming everything is fine above an editor where nothing runs.
     */
    private fun readEnvironment(backend: EnvBackend, envRoot: Path): ManagedEnvironment? {
        val python = backend.pythonExecutable(envRoot)
        if (!Files.isExecutable(python)) return null
        val cfg = readPyvenvCfg(envRoot)
        return ManagedEnvironment(
            backendId = backend.id,
            root = envRoot,
            python = python,
            pythonVersion = cfg?.version,
        )
    }

    private fun readPyvenvCfg(envRoot: Path): PyvenvCfg.Info? = try {
        envRoot.resolve("pyvenv.cfg").takeIf { Files.isRegularFile(it) }
            ?.let { PyvenvCfg.parse(Files.readString(it)) }
    } catch (e: Exception) {
        BasedPythonLog.getInstance(project).warn("could not read pyvenv.cfg in $envRoot: $e")
        null
    }

    private fun readPackages(
        backend: EnvBackend,
        root: Path,
        environment: ManagedEnvironment,
    ): List<EnvPackage> {
        val command = backend.command(EnvOp.ListPackages(environment.python)) ?: return emptyList()
        val result = EnvRunner.run(project, backend, command, root)
        return if (result.isSuccess) backend.parsePackages(result.stdout) else emptyList()
    }

    /**
     * The grouped dependency graph, or nothing.
     *
     * Nothing is an ordinary outcome rather than a failure worth reporting: a backend that has no
     * tree concept answers null to the op, and a project with no lock file has nothing resolved to
     * describe — the command exits non-zero and the view lists what is installed instead. Neither
     * is an error the user needs told about.
     */
    private fun readDependencies(backend: EnvBackend, root: Path): EnvDependencyGraph {
        val command = backend.command(EnvOp.Tree) ?: return EnvDependencyGraph.EMPTY
        val result = EnvRunner.run(project, backend, command, root)
        return if (result.isSuccess) backend.parseTree(result.stdout) else EnvDependencyGraph.EMPTY
    }

    /**
     * The project's modules as they are on disk now, or null when the backend does not divide
     * projects into any — or could not say which it has.
     *
     * Asks the backend's tool for the member list ([EnvOp.ListModules]) and reads the manifests it
     * names. Public, and blocking, for the module operations: every step of a gesture that renames
     * or rewires modules has to act on the layout as the previous step left it, not on the one the
     * last scan saw. Call off the EDT.
     *
     * A listing that fails — a member manifest uv cannot parse, say — is logged and answered with
     * null: the workspace is unloadable by every uv command until it is fixed, and showing a partial
     * list of its modules would offer operations that could only fail.
     */
    fun readModules(backend: EnvBackend, root: Path): ModuleLayout? {
        val command = backend.command(EnvOp.ListModules) ?: return null
        val result = EnvRunner.run(project, backend, command, root)
        if (!result.isSuccess) {
            BasedPythonLog.getInstance(project).warn("could not list the project's modules: ${result.failureMessage()}")
            return null
        }
        return try {
            backend.moduleLayout(root, result.stdout)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            BasedPythonLog.getInstance(project).warn("could not read the project's modules: $e")
            null
        }
    }

    private fun readDrift(backend: EnvBackend, root: Path): EnvDrift {
        val command = backend.command(EnvOp.CheckSync) ?: return EnvDrift.UNKNOWN
        val result = EnvRunner.run(project, backend, command, root)
        // A command that never started says nothing about drift, and must not be read as an exit
        // code the backend would interpret.
        if (result.exitCode == EnvResult.NOT_STARTED) return EnvDrift.UNKNOWN
        return backend.driftFromExitCode(result.exitCode)
    }

    private fun basePath(): Path? = project.basePath?.let { runCatching { Paths.get(it) }.getOrNull() }

    // ---- notification ------------------------------------------------------

    private fun setStatus(next: EnvStatus) {
        status = next
        fire()
    }

    /**
     * Tells the view something changed — but never while a dialog is up.
     *
     * `ModalityState.any()` is the one modality that runs *during* a modal dialog, and what these
     * listeners do is rebuild the dependency tree. Both destructive gestures read the tree, then
     * block: *Remove* computes what to remove and then opens a confirmation, and *Add* snapshots the
     * lists and the module and then opens a dialog the user may sit in for minutes. A refresh
     * landing in that window rebuilds the tree and drops the selection underneath them, so *Yes*
     * runs a command derived from a snapshot the window no longer shows.
     *
     * Non-modal defers those notifications until the dialog closes, which is also when the view can
     * next be looked at. Nothing is lost: [status] and [progress] are already the current values by
     * then, and the deferred listener renders whatever is current rather than replaying a history.
     */
    private fun fire() {
        val application = ApplicationManager.getApplication()
        application.invokeLater(
            {
                listeners.filter { it.modality == ModalityState.nonModal() }.forEach { it.onChange() }
                EnvToolWindow.refreshAvailability(project)
            },
            ModalityState.nonModal(),
            project.disposed,
        )
        // Listeners that asked for another modality — see [addListener] — are told in that one, one
        // dispatch per modality rather than per listener: this runs for every line uv prints.
        listeners.map { it.modality }.filter { it != ModalityState.nonModal() }.distinct().forEach { modality ->
            application.invokeLater(
                { listeners.filter { it.modality == modality }.forEach { it.onChange() } },
                modality,
                project.disposed,
            )
        }
    }

    companion object {
        fun getInstance(project: Project): EnvService = project.service()

        /** How long the manifests have to stop changing before a re-read. See [scheduleRefresh]. */
        private const val REFRESH_DELAY_MILLIS = 1_500L
    }
}
