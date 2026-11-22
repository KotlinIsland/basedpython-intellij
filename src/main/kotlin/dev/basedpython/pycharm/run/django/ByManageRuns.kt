package dev.basedpython.pycharm.run.django

import com.intellij.execution.ExecutionListener
import com.intellij.execution.KillableProcess
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.ByCommandFlags
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.run.ByProcessHandler
import dev.basedpython.pycharm.run.ByRunConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One run of a `manage.py` command: running, or how it ended. */
internal data class ByManageRun(
    val id: Long,
    /** What follows `manage.py`, as the configuration has it. */
    val arguments: String,
    val executorId: String,
    val place: ByManagePlace,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    /** It was ended from the IDE rather than by itself. */
    val stopped: Boolean = false,
    val facts: ByManageFacts = ByManageFacts(),
) {
    val running: Boolean get() = endedAt == null

    val words: List<String> get() = ByManageWords.parse(arguments)

    val command: String? get() = words.firstOrNull()
}

/**
 * Every `by run` configuration of a `manage.py` the IDE has run in the project, however it was started —
 * the *manage.py* window, Run Anything, the run widget, the debugger — with what its output said
 * ([ByManageOutput]).
 *
 * Told of runs by [Listener], which the platform creates for the first one, so a run is followed
 * whether or not the window has been opened.
 */
@Service(Service.Level.PROJECT)
internal class ByManageRuns(private val project: Project, private val scope: CoroutineScope) {

    /** [runs] in the order they started, and what is to be done. */
    internal data class State(val runs: List<ByManageRun> = emptyList(), val warned: List<ByManageWarned> = emptyList()) {
        fun run(id: Long): ByManageRun? = runs.firstOrNull { it.id == id }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * What acts on a run, apart from what is drawn of it. The process of one that has ended is the Run
     * window's to keep, with the console it holds: here it is only found while it is there.
     */
    internal class Process(handler: ProcessHandler, val settings: RunnerAndConfigurationSettings?, val configuration: ByRunConfiguration) {
        /** What it was run with, whatever has been made of its configuration since. */
        val snapshot: ByRunConfiguration = configuration.clone() as ByRunConfiguration

        @Volatile
        private var strong: ProcessHandler? = handler
        private val weak = WeakReference(handler)

        val handler: ProcessHandler? get() = strong ?: weak.get()

        fun ended() {
            strong = null
        }
    }

    private val processes = ConcurrentHashMap<Long, Process>()
    private val followers = ConcurrentHashMap<Long, Follower>()

    /** What each warning was said by, for a command that does as it asks to be run the same way. */
    private val saidBy = ConcurrentHashMap<Any, ByRunConfiguration>()
    private val ids = AtomicLong()

    fun process(id: Long): Process? = processes[id]

    fun saidBy(warned: ByManageWarned): ByRunConfiguration? = saidBy[warned.key]

    /** Calls [onState] with the state now and as it changes, on a background thread, until [parent] is disposed. */
    fun watch(parent: Disposable, onState: (State) -> Unit) {
        val job = scope.launch {
            state.collect {
                onState(it)
                delay(REDRAW_MS)
            }
        }
        Disposer.register(parent) { job.cancel() }
    }

    /** Finds the file run [id] named [path] as it wrote it, and calls [then] with it on the EDT, null when there is none. */
    fun find(id: Long, path: String, then: (VirtualFile?) -> Unit) {
        val base = _state.value.run(id)?.place?.directory ?: return
        scope.launch(Dispatchers.IO) {
            val file = base.toNioPathOrNull()?.resolve(path)?.let { VfsUtil.findFile(it, true) }
            withContext(Dispatchers.EDT) { then(file) }
        }
    }

    /** Drops every run that has ended. */
    fun clearFinished() = forget { it.filterNot(ByManageRun::running) }

    /** Stop, and for a run already stopping, kill, as the Run window's own button does. */
    fun stop(id: Long) {
        val handler = processes[id]?.handler ?: return
        if (handler.isProcessTerminating && handler is KillableProcess && handler.canKillProcess()) handler.killProcess()
        else handler.destroyProcess()
    }

    /** Follows [handler], the process of [profile], when that is a `manage.py` command. */
    fun follow(executorId: String, profile: RunProfile, settings: RunnerAndConfigurationSettings?, handler: ProcessHandler) {
        val configuration = profile as? ByRunConfiguration ?: return
        val options = configuration.options
        if (!runsManage(options.module)) return
        val arguments = options.programArgs.trim()
        val id = ids.incrementAndGet()
        synchronized(processes) {
            if (processes.values.any { it.handler === handler }) return
            processes[id] = Process(handler, settings, configuration)
        }
        val place = ByManagePlace.of(
            options,
            project.basePath.orEmpty(),
            ByManageWords.parse(arguments),
            staged = !ByCommandFlags.ofRunning(project).runsInPlace,
        )
        _state.update { it.copy(runs = it.runs + ByManageRun(id, arguments, executorId, place, System.currentTimeMillis())) }
        val follower = Follower(id, handler)
        followers[id] = follower
        handler.addProcessListener(follower)
        // one that ended as it was being followed has told nobody
        if (handler.isProcessTerminated) follower.ended(handler.exitCode ?: -1)
    }

    /** Output of the program that [profile] runs which is not its process's: a debugger that has its streams. */
    fun printed(profile: RunProfile?, text: String) {
        val id = processes.entries.lastOrNull { it.value.configuration === profile && _state.value.run(it.key)?.running == true }?.key ?: return
        followers[id]?.read(text)
    }

    private inner class Follower(private val id: Long, private val handler: ProcessHandler) : ProcessListener {
        private val output = ByManageOutput(echoes = (handler as? OSProcessHandler)?.hasPty() == true)
        private var generation = 0

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            // the command line the IDE prints, and the exit code, are the system's
            if (ProcessOutputType.isStdout(outputType) || ProcessOutputType.isStderr(outputType)) read(event.text)
        }

        fun read(text: String) = synchronized(output) {
            output.text(text)
            generation++
            publish()
            if (output.mayBePrompt()) askWhenStill(generation)
        }

        /** A line left open may be a question or half of one: it is one when nothing follows for a while. */
        private fun askWhenStill(at: Int) {
            scope.launch {
                delay(STILL_MS)
                synchronized(output) { if (generation == at && !handler.isProcessTerminated && output.still()) publish() }
            }
        }

        private fun publish() = change(id) { if (it.facts == output.facts) it else it.copy(facts = output.facts) }

        /**
         * Sent for a process that has ended by itself too, saying it will be destroyed there as well
         * (measured, 263.7307): it is the IDE's ending of it when there is still a process to end, and
         * when a debug session that has finished does not leave it to end ([ByProcessHandler.leftToFinish]).
         */
        override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
            if ((handler as? ByProcessHandler)?.leftToFinish == true) return
            if ((handler as? BaseProcessHandler<*>)?.process?.isAlive == true) change(id) { it.copy(stopped = true) }
        }

        override fun processTerminated(event: ProcessEvent) = ended(event.exitCode)

        fun ended(exitCode: Int) {
            val facts = synchronized(output) {
                output.finished()
                output.facts
            }
            change(id) { if (it.running) it.copy(facts = facts, endedAt = System.currentTimeMillis(), exitCode = exitCode) else it }
            processes[id]?.ended()
            followers.remove(id)
            forget { runs -> runs.filterNot(ByManageRun::running).sortedBy { it.endedAt }.dropLast(KEPT) }
        }
    }

    private fun change(id: Long, change: (ByManageRun) -> ByManageRun) {
        val before = _state.getAndUpdate { it.changed(id, change) }
        val after = before.changed(id, change)
        for (warned in after.warned) {
            if (warned !in before.warned) processes[id]?.let { saidBy[warned.key] = it.snapshot }
        }
    }

    private fun State.changed(id: Long, change: (ByManageRun) -> ByManageRun): State {
        val before = run(id) ?: return this
        val now = change(before)
        if (now == before) return this
        return State(runs.map { if (it.id == id) now else it }, ByManageWarnings.after(warned, before, now))
    }

    private fun forget(which: (List<ByManageRun>) -> List<ByManageRun>) {
        _state.update { state -> which(state.runs).let { gone -> if (gone.isEmpty()) state else state.copy(runs = state.runs - gone.toSet()) } }
        val kept = _state.value
        processes.keys.removeIf { id -> processes[id]?.let { kept.run(id) == null && it.handler?.isProcessTerminated != false } == true }
        val keys = kept.warned.map { it.key }.toSet()
        saidBy.keys.retainAll(keys)
    }

    /** Whether [module], as a configuration names it, is a `manage.py`: the dotted name of one, or its path. */
    private fun runsManage(module: String): Boolean {
        val name = module.trim().replace('\\', '/').substringAfterLast('/').removeSuffix(".py").substringAfterLast('.')
        if (name == ByDjangoManage.MODULE) return true
        return ByDjangoStructureService.getInstance(project).current()?.projects.orEmpty()
            .any { it.manage?.uri?.substringAfterLast('/')?.removeSuffix(".py") == name }
    }

    /** Tells [ByManageRuns] of every process the IDE runs in the project. */
    internal class Listener(private val project: Project) : ExecutionListener {

        /** Before the process is told to start, so its first line is read. */
        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = follow(executorId, env, handler)

        /** For one that had started by then, as a debug session's has: [processStarting] is not sent for it. */
        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = follow(executorId, env, handler)

        private fun follow(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) =
            getInstance(project).follow(executorId, env.runProfile, env.runnerAndConfigurationSettings, handler)
    }

    companion object {
        private const val KEPT = 30
        private const val STILL_MS = 600L

        /** The least between two drawings: a server under load changes with each request. */
        private const val REDRAW_MS = 100L

        fun getInstance(project: Project): ByManageRuns = project.service()
    }
}
