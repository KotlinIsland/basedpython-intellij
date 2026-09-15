package dev.basedpython.pycharm.lsp.build

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.concurrency.AppExecutorUtil
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.lsp.ext.ByBuildOutputParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.settings.BasedPythonSettingsEffects
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Where `by build` writes, and which generated file belongs to which source — asked of `by`.
 *
 * Every part of the plugin that relates a `.by` to its output goes through here: Go to Related, the
 * generated-file actions, the `byOutPath` template macro, the index exclusion, the test runner's
 * `--ignore`. Each of them used to spell the layout out for itself as `<project>/out/<relative
 * path>.py`, and that was wrong on both counts — `by build` writes to `build/`, and the path inside
 * it follows the module tree, so `src/pkg/main.by` becomes `build/pkg/main.py`. The layout is the
 * build's to decide, and `by/buildOutput` is the build's answer (`by_stage::layout`).
 *
 * ## What is cached, and why
 *
 * Two callers cannot make a request when they need the answer. The index exclusion is read while
 * the platform computes the project's roots, where nothing may block; the template macro runs on
 * the EDT. So:
 *
 *  - **the build directories** are asked for once a `by` server is ready, and kept in the
 *    workspace file — so a project that reopens excludes them from the first scan, rather than
 *    indexing a build tree and then taking it back. Empty until some `by` server has answered for
 *    this project, which is also what keeps a project that never runs `by` from having anything
 *    excluded at all;
 *  - **each file's answer** is kept from the last time it was asked, which [warm] does for every
 *    `.by` file an editor opens, and forgotten when a server starts or the file moves.
 *
 * Everything else asks, from a background thread.
 */
@Service(Service.Level.PROJECT)
@State(name = "BasedPythonBuildDirectories", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ByBuildOutputs(private val project: Project) :
    SimplePersistentStateComponent<ByBuildOutputs.Directories>(Directories()), Disposable {

    class Directories : BaseState() {
        /** Every build directory `by` reported for this project's roots, as local paths. */
        var paths by list<String>()
    }

    /** The last answer for each file, by path. Absent where there was none. */
    private val answers = ConcurrentHashMap<String, ByBuildOutput>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    // A new server may be reading a changed configuration, or be a different `by`.
                    answers.clear()
                    AppExecutorUtil.getAppExecutorService().execute {
                        refreshBuildDirectories()
                        FileEditorManager.getInstance(project).openFiles.forEach(::warm)
                    }
                }
            },
        )
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = warm(file)
            },
        )
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                // A moved, renamed or deleted file has a different place in the build, or none.
                override fun before(events: List<VFileEvent>) {
                    for (event in events) {
                        val path = event.path
                        answers.keys.removeIf { it == path || it.startsWith("$path/") }
                    }
                }
            },
        )
    }

    /** The build directories to keep out of the index. Never blocks. */
    val buildDirectories: List<String> get() = state.paths.toList()

    /**
     * [file]'s place in its project's build, asked of the running `by` server.
     *
     * `null` when there is nothing to ask — no server running, or a file that is not on the local
     * file system (a jar entry, an in-memory file), which no build is made of — or when the server
     * did not answer. Background threads only: the request blocks.
     */
    fun of(file: VirtualFile): ByBuildOutput? {
        if (!file.isInLocalFileSystem) return null
        val client = runningServer() ?: return null
        val uri = client.descriptor.getFileUri(file)
        val answer = client.askBy("by/buildOutput", TIMEOUT_MS) {
            (it as ByServerExtensions).buildOutput(ByBuildOutputParams(uri))
        }.value
        if (answer != null) answers[file.path] = answer else answers.remove(file.path)
        return answer
    }

    /** What [of] last answered for [file], without asking. Safe on any thread. */
    fun cached(file: VirtualFile): ByBuildOutput? = answers[file.path]

    /** Puts an answer in as though `by` had just given it — the seam a test needs, with no server. */
    @TestOnly
    fun remember(file: VirtualFile, answer: ByBuildOutput) {
        answers[file.path] = answer
    }

    /** Asks about [file] in the background, if it is a `.by` file, so [cached] has an answer. */
    fun warm(file: VirtualFile) {
        if (file.fileType != BasedPythonFileType.INSTANCE || runningServer() == null) return
        AppExecutorUtil.getAppExecutorService().execute {
            if (!project.isDisposed && file.isValid) of(file)
        }
    }

    /**
     * Asks for the build directory of every root of the project, and rescans the roots when the
     * answer is not what is stored — which is what makes the exclusion take effect.
     */
    private fun refreshBuildDirectories() {
        val roots = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            if (project.isDisposed) return@computeBlocking emptyList()
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            (ProjectRootManager.getInstance(project).contentRoots.toList() + listOfNotNull(base)).distinct()
        }
        val directories = roots.mapNotNull { of(it)?.buildDirectory }.distinct().sorted()
        // Nothing answered: keep what the last server said rather than un-excluding a build tree
        // because this one was slow.
        if (directories.isEmpty() || directories == state.paths) return
        state.paths = directories.toMutableList()
        BasedPythonSettingsEffects.rescanRoots(project)
    }

    private fun runningServer(): LspClient? =
        LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    override fun dispose() {
        answers.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] publishes under. */
        private const val BY_SERVER = "by"

        /** A lookup over the project's file list, not a build: an editor request's bound is plenty. */
        private const val TIMEOUT_MS = 2_000

        fun getInstance(project: Project): ByBuildOutputs = project.service()
    }
}
