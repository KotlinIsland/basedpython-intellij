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

    /**
     * True while a refresh or an operation is in flight, so the view can disable what must not run
     * twice.
     *
     * A depth counter rather than a flag, because the two overlap by design: a gesture ends by
     * refreshing, and a refresh can be running when a gesture starts. With a flag, whichever
     * finished first would clear it and re-enable a *Sync* button while the sync was still going.
     */
    private val busyDepth = java.util.concurrent.atomic.AtomicInteger()

    val busy: Boolean get() = busyDepth.get() > 0

    /**
     * Marks the service busy for the duration of [block], on the calling thread.
     *
     * How [EnvOperations] keeps a whole multi-step gesture — install, create, sync — reading as one
     * busy stretch rather than three.
     */
    fun <T> busyWhile(block: () -> T): T {
        setBusy(true)
        return try {
            block()
        } finally {
            setBusy(false)
        }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Guards against a second refresh while one is in flight. */
    private val refreshing = AtomicBoolean(false)

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

    /** Registers [listener], called on the EDT after every change, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    fun refreshIfNeeded() {
        if (!scanned) refresh()
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
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(REFRESH_DELAY_MILLIS)
            if (!refresh()) scheduleRefresh()
        }
    }

    /** Re-reads, unless a read is already in flight; true when this call started one. */
    fun refresh(): Boolean {
        if (!refreshing.compareAndSet(false, true)) return false
        setBusy(true)
        scope.launch(Dispatchers.IO) {
            try {
                setStatus(scan())
            } catch (e: Exception) {
                // Reading another tool's output and another tool's files; anything it throws is
                // about one project's configuration and must not take the service down.
                BasedPythonLog.getInstance(project).warn("environment scan failed: $e")
                setStatus(EnvStatus.unknown(basePath()).copy(error = e.message ?: e.toString()))
            } finally {
                scanned = true
                refreshing.set(false)
                setBusy(false)
            }
        }
        return true
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
            // Before the early return below, deliberately. A project's modules are readable from its
            // manifests alone, and the state that most needs them read is the one that returns
            // early: a workspace whose environment has not been created yet is exactly when someone
            // opens the structure page to add the module they are about to sync.
            modules = readModules(backend, root),
        )
        if (tool == null || base.environment == null) return base
        return base.copy(
            packages = readPackages(backend, root, base.environment),
            drift = readDrift(backend, root),
            dependencies = readDependencies(backend, root),
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
    private fun readDependencies(backend: EnvBackend, root: Path): List<EnvDependencyGroup> {
        val command = backend.command(EnvOp.Tree) ?: return emptyList()
        val result = EnvRunner.run(project, backend, command, root)
        return if (result.isSuccess) backend.parseTree(result.stdout) else emptyList()
    }

    /**
     * The project's modules, or null when the backend does not divide projects into any.
     *
     * Wrapped, because this walks directories the user controls: a `members` glob pointing at a
     * symlink loop or a directory the IDE cannot read must degrade to "no modules" rather than take
     * the whole scan — and with it the environment view — down.
     */
    private fun readModules(backend: EnvBackend, root: Path): ModuleLayout? =
        try {
            backend.moduleLayout(root)
        } catch (e: Exception) {
            BasedPythonLog.getInstance(project).warn("could not read the project's modules: $e")
            null
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

    private fun setBusy(value: Boolean) {
        if (value) busyDepth.incrementAndGet() else busyDepth.decrementAndGet()
        fire()
    }

    private fun fire() {
        ApplicationManager.getApplication().invokeLater(
            {
                listeners.forEach { it() }
                EnvToolWindow.refreshAvailability(project)
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    companion object {
        fun getInstance(project: Project): EnvService = project.service()

        /** How long the manifests have to stop changing before a re-read. See [scheduleRefresh]. */
        private const val REFRESH_DELAY_MILLIS = 1_500L
    }
}
