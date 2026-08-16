package dev.basedpython.pycharm.tasks

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
 * The tasks a project's hook configurations declare, and what the last run said about them.
 *
 * Cheap by construction: finding tasks is reading at most nine file names at the project root and
 * parsing the ones that are there, so this re-scans whenever one of them changes rather than making
 * the user press Refresh. Contrast the test view, whose data costs a `by run` and is therefore
 * debounced and asked for explicitly.
 *
 * Verdicts are kept per task and survive a re-scan ([ByTaskNode.key] is built from what a task *is*,
 * not from the object that was parsed), so editing a config file does not wipe the record of what
 * passed.
 */
@Service(Service.Level.PROJECT)
internal class ByTaskService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    /** Nothing to release: this exists so listeners can be tied to the service's own lifetime. */
    override fun dispose() = Unit

    /** What the view has to show right now. */
    sealed interface State {
        /** Nothing scanned yet — not the same as "this project has no hooks". */
        data object Idle : State

        /** The result of the last scan; empty when the project configures none of the four tools. */
        data class Scanned(val files: List<ByTaskNode>) : State
    }

    @Volatile
    var state: State = State.Idle
        private set

    /** The task tree as last scanned; empty until something has scanned. */
    val files: List<ByTaskNode>
        get() = (state as? State.Scanned)?.files.orEmpty()

    /**
     * What the last run said about each task, keyed by [ByTaskNode.key].
     *
     * Replaced per task, so running one hook leaves every other verdict standing — the point of
     * showing them in a list of what a project *can* run.
     */
    @Volatile
    var outcomes: Map<String, ByTaskState> = emptyMap()
        private set

    /**
     * Whether runs from this view are asked for every file rather than only the staged ones.
     *
     * Project-level and persisted, because it is a property of how a repository is worked on rather
     * than of a single run: someone who wants `--all-files` wants it for the next hook too.
     */
    var allFiles: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(ALL_FILES_KEY, true)
        set(value) = PropertiesComponent.getInstance(project).setValue(ALL_FILES_KEY, value, true)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val outcomeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Guards against a second scan while one is in flight. */
    private val running = AtomicBoolean(false)

    /** The pending debounced re-scan, if a configuration file has changed recently. */
    private var syncJob: Job? = null

    /** Registers [listener], called on the EDT after every [state] change, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Registers [listener], called on the EDT after every [outcomes] change. */
    fun addOutcomeListener(parent: Disposable, listener: () -> Unit) {
        outcomeListeners += listener
        Disposer.register(parent) { outcomeListeners -= listener }
    }

    /** Scans unless something already has. */
    fun refreshIfNeeded() {
        if (state == State.Idle) refresh()
    }

    /**
     * Re-scans a short while after the configuration stops changing.
     *
     * Short, because a scan is a handful of file reads: long enough that a file saved by a formatter
     * right after the user's own save is one scan, not two, and short enough that a hook added to
     * the file appears while the user is still looking at it.
     */
    fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_DELAY_MILLIS)
            if (!refresh()) scheduleSync()
        }
    }

    /** Re-scans, unless a scan is already in flight; true when this call started one. */
    fun refresh(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.IO) {
            try {
                setState(State.Scanned(scan()))
            } catch (e: Exception) {
                // A scan reads files and parses text; anything it throws is about one project's
                // configuration and must not take the service down with it.
                BasedPythonLog.getInstance(project).warn("hook task scan failed: $e")
                setState(State.Scanned(emptyList()))
            } finally {
                running.set(false)
            }
        }
        return true
    }

    /**
     * The task tree for the project as it is on disk.
     *
     * On disk deliberately, rather than through the editors: these files are read by another process
     * when a hook runs, so unsaved changes are exactly the tasks that would *not* run. Showing the
     * saved file keeps the view and the tool in agreement.
     */
    private fun scan(): List<ByTaskNode> {
        val base = project.basePath?.let { Paths.get(it) } ?: return emptyList()
        val files = ByTaskScan.scan { name -> read(base.resolve(name)) }
        if (files.none { it.runner == ByTaskRunner.PRE_COMMIT }) return files
        // Only worth resolving binaries once something needs one of them; both lookups walk the
        // project's venv and PATH.
        val preferred = ByTaskRunners.preferred(
            preCommitFound = ByTaskLaunch.isAvailable(project, ByTaskRunner.PRE_COMMIT),
            prekFound = ByTaskLaunch.isAvailable(project, ByTaskRunner.PREK),
        )
        return files.map { if (it.runner == ByTaskRunner.PRE_COMMIT) it.withRunner(preferred) else it }
    }

    private fun read(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: Exception) {
        // Unreadable and absent are the same answer here: no tasks from this file.
        BasedPythonLog.getInstance(project).warn("could not read $path: $e")
        null
    }

    /** Records [state] for the task [key], as reported by a run. */
    fun setOutcome(key: String, state: ByTaskState) {
        outcomes = outcomes + (key to state)
        fireOutcomes()
    }

    private fun fireOutcomes() {
        ApplicationManager.getApplication().invokeLater(
            { outcomeListeners.forEach { it() } },
            ModalityState.any(),
            project.disposed,
        )
    }

    private fun setState(next: State) {
        state = next
        ApplicationManager.getApplication().invokeLater(
            {
                listeners.forEach { it() }
                // A project can grow its first `.pre-commit-config.yaml` at any time, and the tool
                // window's availability was decided when the project opened.
                ByTaskToolWindow.refreshAvailability(project)
            },
            ModalityState.any(),
            project.disposed,
        )
    }

    companion object {
        fun getInstance(project: Project): ByTaskService = project.service()

        /** How long the configuration has to stop changing before re-scanning. */
        private const val SYNC_DELAY_MILLIS = 500L

        private const val ALL_FILES_KEY = "basedpython.tasks.allFiles"
    }
}
