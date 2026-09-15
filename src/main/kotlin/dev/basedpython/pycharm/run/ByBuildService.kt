package dev.basedpython.pycharm.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.run.watch.WatchModeState
import dev.basedpython.pycharm.util.Debounced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every `by build` the IDE starts for a project, one at a time.
 *
 * Two builds of one project write the same output tree, and a watch-mode build landing on top of
 * the one a run is waiting for is how a program ends up importing half of each. So the build a run
 * configuration asks for first and the builds watch mode starts on save share one lock.
 *
 * The builds run in the service's scope, which ends with the project and with the plugin: closing
 * either kills the process rather than leaving it writing into a project nobody has open.
 */
@Service(Service.Level.PROJECT)
internal class ByBuildService(private val project: Project, private val scope: CoroutineScope) {

    private val lock = Mutex()

    /** Watch mode's builds: a burst of saves is one build, and a save during a build is one more. */
    private val watchBuilds = Debounced(scope, WATCH_DEBOUNCE) { watchBuild() }

    /** Asks for a watch-mode build of this project. Safe from any thread. */
    fun requestWatchBuild() = watchBuilds.request()

    private suspend fun watchBuild() {
        // Checked when the build would start, not when it was asked for: turning watch mode off
        // straight after a save should stop the build that save asked for.
        if (!WatchModeState.isEnabled(project)) return
        val cmd = try {
            byCommandLine(project, ByBuildOptions(), byArguments("build", "--min-version", "", emptyList(), ""))
        } catch (_: ExecutionException) {
            ByCli.notifyBinaryMissing(project, "by")
            return
        }
        val output = build(cmd, "Watch: by build")
        if (output.exitCode != 0) LOG.info("watch-mode by build exited ${output.exitCode}: ${output.stderr}")
    }

    /**
     * Runs [cmd] once no other build of this project is running, under a cancellable background
     * progress named [title].
     */
    suspend fun build(cmd: GeneralCommandLine, title: String): ProcessOutput = lock.withLock {
        withBackgroundProgress(project, title, cancellable = true) { runCapturing(cmd) }
    }

    /**
     * [build], for a caller on a plain background thread that has to wait for the answer.
     *
     * Null when the build was cancelled — from its progress, or by the project closing. The work
     * itself belongs to the service's scope rather than to the waiting thread, so it is cancelled
     * by what owns the project, not merely abandoned by whoever asked; a cancelled *caller* cancels
     * the build too, and gets its cancellation rethrown.
     */
    fun buildBlocking(cmd: GeneralCommandLine, title: String): ProcessOutput? {
        val job = scope.async { build(cmd, title) }
        return try {
            runBlockingMaybeCancellable { job.await() }
        } catch (e: CancellationException) {
            if (job.isCancelled) return null
            job.cancel()
            throw e
        }
    }

    companion object {
        fun getInstance(project: Project): ByBuildService = project.service()

        private val LOG = Logger.getInstance(ByBuildService::class.java)

        /** Long enough that "save all", or a formatter saving after the user, is one build. */
        private val WATCH_DEBOUNCE = 500.milliseconds
    }
}

/**
 * Runs [cmd] to completion and returns what it printed, destroying the process — and everything it
 * started — if the calling coroutine is cancelled first.
 *
 * `CapturingProcessHandler.runProcess` blocks its thread with no way in, so a build started from
 * there outlived every Cancel it was given.
 *
 * A [timeout] destroys the process when it elapses and returns what was printed until then, with
 * [ProcessOutput.isTimeout] set.
 */
internal suspend fun runCapturing(cmd: GeneralCommandLine, timeout: Duration? = null): ProcessOutput {
    val handler = KillableProcessHandler(cmd)
    handler.setShouldKillProcessSoftly(false)
    handler.setShouldDestroyProcessRecursively(true)
    val output = ProcessOutput()
    val exited = CompletableDeferred<Unit>()
    handler.addProcessListener(object : CapturingProcessAdapter(output) {
        override fun processTerminated(event: ProcessEvent) {
            super.processTerminated(event)
            exited.complete(Unit)
        }
    })
    handler.startNotify()
    try {
        if (timeout == null) exited.await()
        else if (withTimeoutOrNull(timeout) { exited.await() } == null) output.setTimeout()
    } finally {
        if (!handler.isProcessTerminated) handler.destroyProcess()
    }
    return output
}
