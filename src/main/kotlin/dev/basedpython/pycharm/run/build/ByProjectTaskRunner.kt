package dev.basedpython.pycharm.run.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.process.BuildProcessHandler
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.execution.ExecutionException
import com.intellij.execution.filters.RegexpFilter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.run.ByBuildService
import dev.basedpython.pycharm.run.projectBuildCommandLine
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Build | Build Project, Build Module and Rebuild Project in a basedpython project: `by build`,
 * reported in the Build tool window.
 *
 * The build is the one watch mode and *Run `by build` first* run — the same command, through
 * [ByBuildService], whose lock keeps any two of them from writing one tree at once. Rebuild runs it
 * too: `by build` already takes back whatever the previous build wrote and this one did not, so
 * there is no stale output for a rebuild to clear first.
 *
 * One `by build` answers every module task it is given, because it builds the project, not a module.
 *
 * Only IntelliJ IDEA has these actions — they come with its Java support — and there, without this,
 * the platform's own compiler took the module and reported every `.by` file as nothing to do.
 */
class ByProjectTaskRunner : ProjectTaskRunner() {

    /**
     * A module of a basedpython project: one whose content holds the project's base directory, which
     * is where [BasedPythonProjectDetector] found what makes the project basedpython. A module
     * elsewhere in the project — a Java one beside it — stays the platform's to build.
     */
    override fun canRun(project: Project, projectTask: ProjectTask, context: ProjectTaskContext?): Boolean {
        if (projectTask !is ModuleBuildTask) return false
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return false
        return holdsProjectBase(projectTask.module)
    }

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val result = AsyncPromise<Result>()
        ByBuildView(project).start { result.setResult(it) }
        return result
    }

    private fun holdsProjectBase(module: Module): Boolean {
        val base = module.project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return false
        return ModuleRootManager.getInstance(module).contentRoots.any { VfsUtilCore.isAncestor(it, base, false) }
    }
}

/**
 * One project build, shown in the Build tool window: what `by build` printed as console text, each
 * diagnostic as a node that opens its place, and a Stop that ends the build.
 */
internal class ByBuildView(private val project: Project) {

    private val title = BasedPythonBundle.message("build.view.title")

    fun start(finished: (ProjectTaskRunner.Result) -> Unit) {
        val stop = Stop(title)
        val descriptor = DefaultBuildDescriptor(Any(), title, project.basePath.orEmpty(), System.currentTimeMillis())
            // The console it is shown in is what closes with the plugin; see ByBuildWindow.
            .withProcessHandler(stop) { console -> ByBuildWindow.getInstance(project).shown(console, stop) }
            // `by` names its sources relative to where it was started, which is the project base.
            .withExecutionFilter(
                RegexpFilter(project, "${RegexpFilter.FILE_PATH_MACROS}:${RegexpFilter.LINE_MACROS}:${RegexpFilter.COLUMN_MACROS}"),
            )
        val progress = BuildViewManager.createBuildProgress(project).start(
            object : BuildProgressDescriptor {
                override fun getTitle(): String = this@ByBuildView.title
                override fun getBuildDescriptor(): DefaultBuildDescriptor = descriptor
            },
        )
        stop.progress = progress
        stop.startNotify()
        val cmd = try {
            projectBuildCommandLine(project).withEnvironment(ByBuildDiagnostics.FORMAT_VARIABLE, ByBuildDiagnostics.FORMAT)
        } catch (e: ExecutionException) {
            stop.ended(-1) {
                progress.output("${e.message}\n", ProcessOutputType.STDERR)
                progress.fail()
            }
            finished(ByBuildResult.FAILURE)
            return
        }
        val service = ByBuildService.getInstance(project)
        stop.build = service.launch {
            val result = try {
                val output = service.build(cmd, title, Output(progress))
                stop.ended(output.exitCode) {
                    if (output.exitCode == 0) {
                        progress.finish()
                    } else {
                        // Said even when `by` said why, because a `by` killed by a signal says nothing.
                        progress.output(BasedPythonBundle.message("build.view.exited", output.exitCode) + "\n", ProcessOutputType.STDERR)
                        progress.fail()
                    }
                }
                if (output.exitCode == 0) ByBuildResult.SUCCESS else ByBuildResult.FAILURE
            } catch (e: CancellationException) {
                // Stopped — from the window, the progress, or by the project or plugin going away,
                // when there is no window left to tell.
                stop.ended(-1) { if (!project.isDisposed) progress.cancel() }
                finished(ByBuildResult.ABORTED)
                throw e
            } catch (e: ExecutionException) {
                stop.ended(-1) {
                    progress.output("${e.message}\n", ProcessOutputType.STDERR)
                    progress.fail()
                }
                ByBuildResult.FAILURE
            }
            withContext(Dispatchers.IO) { refreshBuildDirectories() }
            finished(result)
        }
    }

    /**
     * Has the IDE look at what the build wrote now, rather than whenever the file watcher reports
     * it, so a generated file open in an editor shows this build once the window says it is over.
     */
    private fun refreshBuildDirectories() {
        val directories = ByBuildOutputs.getInstance(project).buildDirectories
            .mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Paths.get(it)) }
        if (directories.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *directories.toTypedArray())
    }

    /** The build's output, a line at a time: a diagnostic as a node, anything else as text. */
    private class Output(private val progress: BuildProgress<BuildProgressDescriptor>) : ProcessListener {
        private val partial = HashMap<Key<*>, StringBuilder>()

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType == ProcessOutputType.SYSTEM) return
            val lines = synchronized(partial) {
                val pending = partial.getOrPut(outputType) { StringBuilder() }.append(event.text)
                val end = pending.lastIndexOf("\n") + 1
                pending.substring(0, end).also { pending.delete(0, end) }
            }
            lines.lineSequence().filter { it.isNotEmpty() }.forEach { line(it, outputType) }
        }

        override fun processTerminated(event: ProcessEvent) {
            val rest = synchronized(partial) { partial.map { (type, text) -> type to text.toString() } }
            for ((type, text) in rest) if (text.isNotEmpty()) line(text, type)
        }

        private fun line(text: String, outputType: Key<*>) {
            val diagnostic = ByBuildDiagnostics.parse(text)
            if (diagnostic == null) {
                progress.output(
                    "$text\n",
                    if (ProcessOutputType.isStderr(outputType)) ProcessOutputType.STDERR else ProcessOutputType.STDOUT,
                )
                return
            }
            val kind = when (diagnostic.severity) {
                ByBuildDiagnostic.Severity.ERROR -> MessageEvent.Kind.ERROR
                ByBuildDiagnostic.Severity.WARNING -> MessageEvent.Kind.WARNING
                ByBuildDiagnostic.Severity.INFO -> MessageEvent.Kind.INFO
            }
            val file = diagnostic.file
            if (file == null) {
                progress.message(diagnostic.title, diagnostic.message, kind, null)
            } else {
                // `by` counts from 1, the platform from 0.
                val position = FilePosition(
                    Path.of(file),
                    (diagnostic.line ?: 1) - 1,
                    (diagnostic.column ?: 1) - 1,
                )
                progress.fileMessage(diagnostic.title, diagnostic.message, kind, position)
            }
        }
    }

    /**
     * The Build window's Stop, which cancels the build — and the build's cancellation is what ends
     * the `by` process and everything it started (see [dev.basedpython.pycharm.run.runCapturing]).
     *
     * Lets go of the build once it is over, since the window keeps this for as long as it shows it.
     */
    class Stop(private val name: String) : BuildProcessHandler() {
        @Volatile
        var build: Job? = null

        /** The build's place in the window, which [ended] reports the end to. */
        lateinit var progress: BuildProgress<BuildProgressDescriptor>

        private val over = AtomicBoolean()

        override fun getExecutionName(): String = name

        override fun destroyProcessImpl() {
            build?.cancel()
        }

        override fun detachProcessImpl() {
            build?.cancel()
        }

        override fun detachIsDefault(): Boolean = false

        override fun getProcessInput(): OutputStream? = null

        /**
         * Reports the build over — [report] tells the window how — once: whichever of the build and
         * [abandon] gets here first.
         */
        fun ended(exitCode: Int, report: () -> Unit) {
            if (!over.compareAndSet(false, true)) return
            report()
            build = null
            notifyProcessTerminated(exitCode)
        }

        /**
         * Cancels the build and reports it cancelled at once, rather than when its cancellation is
         * done, so that a tab closed straight after has no running build to ask the user about.
         */
        fun abandon() {
            build?.cancel()
            ended(-1) { progress.cancel() }
        }
    }
}

/**
 * How a build ended, as the platform's task runner reads it. The platform's own `TaskRunnerResults`
 * is experimental API.
 */
private enum class ByBuildResult(private val aborted: Boolean, private val errors: Boolean) : ProjectTaskRunner.Result {
    SUCCESS(aborted = false, errors = false),
    FAILURE(aborted = false, errors = true),
    ABORTED(aborted = true, errors = false),
    ;

    override fun isAborted(): Boolean = aborted

    override fun hasErrors(): Boolean = errors
}
