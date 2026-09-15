package dev.basedpython.pycharm.run.model

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.messages.Topic
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByEntryPointParams
import dev.basedpython.pycharm.lsp.ext.ByEntryPointResponse
import dev.basedpython.pycharm.lsp.ext.ByRunModulesParams
import dev.basedpython.pycharm.lsp.ext.ByRunModulesProjectReply
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTestItemsParams
import dev.basedpython.pycharm.run.main.ByMainFunction
import dev.basedpython.pycharm.util.Debounced
import java.net.URI
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import kotlin.time.Duration.Companion.milliseconds

/**
 * What `by` says about how this project's modules run and which tests they hold — the one place the
 * run and test features read it from.
 *
 * Every answer here is the server's: `by/entryPoint` for a module's `main` and `__main__` guards,
 * `by/testItems` for the tests pytest would collect, `by/runModules` for the name `by run` runs each
 * file under and the project's `run.main`, `by/buildOutput` for where `by run` stages a test file.
 * None of it is worked out from the source text.
 *
 * ## Who may wait, and who may not
 *
 * A request to the server is background-only, and some callers cannot wait even there. So there are
 * two ways to ask:
 *
 *  - [entryPoint] and [testItems] **ask and wait** off the EDT — the line-marker pass is a
 *    cancellable background read action, and `sendRequestSync` polls for that cancellation — and
 *    serve only what is already known on the EDT, starting a request behind the answer.
 *  - [cachedTestItems], [moduleName], [projectTests], [stagedPath] and [configuredMain] **never wait**: a run
 *    configuration producer runs inside a read action while a menu is being built, and blocking it
 *    on a process would freeze whoever is holding the menu open. They answer from what the last
 *    request said, and start one when nothing has been said yet.
 *
 * Per-file answers are held against the document's modification stamp, so an edit is never answered
 * with what the file said before it. Project-wide answers are refreshed a moment after the project's
 * files change on disk and whenever the server (re)starts; [CHANGED] says when they have.
 */
@Service(Service.Level.PROJECT)
internal class ByProgramModel(private val project: Project, scope: CoroutineScope) : Disposable {

    /** An answer about one file, and the revision of the file it was given for. */
    private class Stamped<T>(val stamp: Long, val value: T)

    /** Weak keys: a file that is gone takes its answers with it. */
    private val entryPoints: MutableMap<VirtualFile, Stamped<ByEntryPoint>> = ContainerUtil.createConcurrentWeakMap()
    private val testFiles: MutableMap<VirtualFile, Stamped<List<ByTestItem>>> = ContainerUtil.createConcurrentWeakMap()

    /** Files a background request is already out for, so a stalled server is asked once. */
    private val asking: MutableSet<Pair<String, VirtualFile>> = ContainerUtil.newConcurrentSet()

    @Volatile
    private var modules: ByRunModules? = null

    @Volatile
    private var tests: ByProjectTests? = null

    private val projectRefresh = Debounced(scope, REFRESH_DEBOUNCE) { refreshProject() }

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /** A restarted server is a new project database; what the old one said may no longer hold. */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    entryPoints.clear()
                    testFiles.clear()
                    requestProjectRefresh()
                }
            },
        )
    }

    // ------------------------------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------------------------------

    /**
     * How [file] runs, as it stands right now; null while that is not known.
     *
     * Waits for the server off the EDT; on it, serves only an answer already held for this revision.
     */
    fun entryPoint(file: VirtualFile): ByEntryPoint? =
        stamped(entryPoints, file) ?: askOrSchedule(ENTRY_POINT, file) { askEntryPoint(file) }

    /** What [entryPoint] already knows about [file] at its current revision, without asking. */
    fun cachedEntryPoint(file: VirtualFile): ByEntryPoint? =
        stamped(entryPoints, file) ?: run { schedule(ENTRY_POINT, file) { askEntryPoint(file) }; null }

    /**
     * The command line of the module a `by run` configuration names — [module], or the project's
     * `run.main` when it is blank — or null when that module has no `main` to fill in.
     *
     * Background only: this resolves the name and reads the file behind it, and waits for both.
     */
    fun mainFor(module: String): ByMainFunction? {
        check(!ApplicationManager.getApplication().isDispatchThread) { "asks the server; not on the EDT" }
        val client = anyClient() ?: return null
        val reply = client.askBy("by/runModules", REQUEST_TIMEOUT_MS) {
            (it as ByServerExtensions).runModules(ByRunModulesParams(module.trim().ifBlank { null }))
        }.value ?: return null
        val project = reply.projects.firstOrNull() ?: return null
        val target = if (module.isBlank()) project.main else project.requested
        val file = target?.uri?.let(::fileAt) ?: return null
        return entryPoint(file)?.commandLine
    }

    /**
     * [mainFor], from whichever thread: on the EDT it waits under a modal, cancellable progress named
     * [title] — for a caller that has just been clicked and has nothing to show until it knows —
     * and elsewhere it simply waits.
     */
    fun mainForWaiting(module: String, title: String): ByMainFunction? {
        if (!ApplicationManager.getApplication().isDispatchThread) return mainFor(module)
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<ByMainFunction?, RuntimeException> { mainFor(module) },
            title,
            true,
            project,
        )
    }

    private fun askEntryPoint(file: VirtualFile): ByEntryPoint? {
        val client = byServerFor(project, file) ?: return null
        val stamp = stampOf(file)
        val answer = client.askBy("by/entryPoint", REQUEST_TIMEOUT_MS) {
            (it as ByServerExtensions).entryPoint(ByEntryPointParams(client.descriptor.getFileUri(file)))
        }
        val found = when (answer) {
            is ByAnswer.Answer -> ByEntryPoint.of(answer.value)
            // No project holds the file: it runs as nothing, which is an answer worth keeping.
            ByAnswer.None -> ByEntryPoint.of(ByEntryPointResponse())
            // Not remembered: nobody answering is not the same as the file having no entry point.
            ByAnswer.Failed -> return null
        }
        entryPoints[file] = Stamped(stamp, found)
        return found
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    /** The tests [file] holds as it stands right now; null while that is not known. */
    fun testItems(file: VirtualFile): List<ByTestItem>? =
        stamped(testFiles, file) ?: askOrSchedule(TEST_ITEMS, file) { askTestItems(file) }

    /** What [testItems] already knows about [file] at its current revision, without asking. */
    fun cachedTestItems(file: VirtualFile): List<ByTestItem>? =
        stamped(testFiles, file) ?: run { schedule(TEST_ITEMS, file) { askTestItems(file) }; null }

    /**
     * The tests of every file of the project, as of the last project-wide answer; null before there
     * has been one. Never waits.
     */
    fun projectTests(): ByProjectTests? = tests ?: run { requestProjectRefresh(); null }

    /**
     * The path pytest names [file] by when it collects the tree `by run` stages ([stagedPath]), as of
     * the last `by/buildOutput` answer about it; null when there has been none, or it places no
     * source. Never waits.
     *
     * The answer is asked for each test file whenever the project-wide answers are refreshed, and for
     * every `.by` file an editor opens ([ByBuildOutputs.warm]).
     */
    fun stagedPath(file: VirtualFile): String? = ByBuildOutputs.getInstance(project).cached(file)?.stagedPath

    /** The test file `by run` stages at [path] — the inverse of [stagedPath]. Never waits. */
    fun stagedFile(path: String): VirtualFile? =
        (tests?.byFile?.keys.orEmpty().asSequence() + testFiles.keys.asSequence())
            .firstOrNull { it.isValid && stagedPath(it) == path }

    private fun askTestItems(file: VirtualFile): List<ByTestItem>? {
        val client = byServerFor(project, file) ?: return null
        val stamp = stampOf(file)
        val answer = client.askBy("by/testItems", REQUEST_TIMEOUT_MS) {
            (it as ByServerExtensions).testItems(ByTestItemsParams(client.descriptor.getFileUri(file)))
        }
        val found = when (answer) {
            is ByAnswer.Answer -> answer.value.files.firstOrNull()?.tests.orEmpty().map(ByTestItem::of)
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return null
        }
        testFiles[file] = Stamped(stamp, found)
        return found
    }

    // ------------------------------------------------------------------------------------------
    // Module names
    // ------------------------------------------------------------------------------------------

    /**
     * The name `by run` runs [file] under, as of the last project-wide answer; null when it runs under
     * none, or when there has been no answer yet. Never waits.
     */
    fun moduleName(file: VirtualFile): String? {
        val known = modules ?: run { requestProjectRefresh(); return null }
        return known.byPath[file.path]
    }

    /**
     * The project's configured `run.main`, as of the last project-wide answer. [ByRunModules.known]
     * is false while there has been none, which is not the same as there being no `run.main`.
     */
    fun configuredMain(): ByRunModules.Main =
        modules?.let { ByRunModules.Main(known = true, module = it.main) }
            ?: run { requestProjectRefresh(); ByRunModules.Main(known = false, module = null) }

    /** Asks for the project-wide answers again, a moment from now. Safe from any thread. */
    fun requestProjectRefresh() = projectRefresh.request()

    private suspend fun refreshProject() {
        val client = anyClient() ?: return
        val (runModules, projectTests) = withContext(Dispatchers.IO) {
            val runModules = client.askBy("by/runModules", REQUEST_TIMEOUT_MS) {
                (it as ByServerExtensions).runModules(ByRunModulesParams())
            }
            val projectTests = client.askBy("by/testItems", PROJECT_TESTS_TIMEOUT_MS) {
                (it as ByServerExtensions).testItems(ByTestItemsParams())
            }
            runModules to projectTests
        }
        runModules.value?.let { reply ->
            val next = ByRunModules.of(reply.projects.firstOrNull() ?: ByRunModulesProjectReply(), ::pathOf)
            val changed = next != modules
            modules = next
            // The run gutter's tooltip names what a module was last run with, by its module name.
            if (changed) restartDaemon()
        }
        projectTests.value?.let { reply ->
            val next = ByProjectTests(reply.files.mapNotNull { file ->
                val virtualFile = file.uri?.let(::fileAt) ?: return@mapNotNull null
                virtualFile to file.tests.map(ByTestItem::of)
            }.toMap())
            // Where each test file is staged, before anyone is told there are tests to run: a target
            // and a node id are built from it. Asked only where it is not known, since it changes
            // when a file moves or the server restarts, both of which forget it.
            val outputs = ByBuildOutputs.getInstance(project)
            withContext(Dispatchers.IO) {
                for (file in next.byFile.keys) {
                    if (file.extension == BY_EXTENSION && outputs.cached(file) == null) outputs.of(file)
                }
            }
            tests = next
        }
        if (!project.isDisposed) project.messageBus.syncPublisher(CHANGED).run()
    }

    // ------------------------------------------------------------------------------------------

    private fun <T> stamped(map: Map<VirtualFile, Stamped<T>>, file: VirtualFile): T? =
        map[file]?.takeIf { it.stamp == stampOf(file) }?.value

    /** Asks now when this thread may wait, and otherwise starts the request behind the caller. */
    private fun <T> askOrSchedule(kind: String, file: VirtualFile, ask: () -> T?): T? {
        if (!ApplicationManager.getApplication().isDispatchThread) return ask()
        schedule(kind, file, ask)
        return null
    }

    private fun <T> schedule(kind: String, file: VirtualFile, ask: () -> T?) {
        // Nothing to ask: no thread is worth starting, and in a test there is never a server.
        if (byServerFor(project, file) == null) return
        val key = kind to file
        if (!asking.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!project.isDisposed) ask()
            } catch (_: ProcessCanceledException) {
                // The project closed under the request; the next caller asks again.
            } finally {
                asking.remove(key)
            }
        }
    }

    private fun stampOf(file: VirtualFile): Long =
        FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp ?: file.modificationStamp

    private fun anyClient(): LspClient? =
        LspClientManager.getInstance(project)
            .getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    private fun restartDaemon() {
        ApplicationManager.getApplication().invokeLater(
            { DaemonCodeAnalyzer.getInstance(project).restart(RESTART_REASON) },
            project.disposed,
        )
    }

    override fun dispose() {
        entryPoints.clear()
        testFiles.clear()
        asking.clear()
    }

    /** The seams a test needs: answers put in as though the server had just given them. */
    @TestOnly
    fun rememberEntryPoint(file: VirtualFile, reply: ByEntryPointResponse) {
        entryPoints[file] = Stamped(stampOf(file), ByEntryPoint.of(reply))
    }

    @TestOnly
    fun rememberTestItems(file: VirtualFile, items: List<ByTestItem>) {
        testFiles[file] = Stamped(stampOf(file), items)
    }

    /** Adds to the project-wide answer the names `by run` runs [names]' files under. */
    @TestOnly
    fun rememberModuleNames(names: Map<VirtualFile, String>, main: String? = null) {
        val known = modules ?: ByRunModules(emptyMap(), null)
        modules = ByRunModules(known.byPath + names.mapKeys { it.key.path }, main ?: known.main)
    }

    /** Adds [items] to the project-wide answer about tests, as [file]'s. */
    @TestOnly
    fun rememberProjectTests(file: VirtualFile, items: List<ByTestItem>) {
        tests = ByProjectTests((tests?.byFile ?: emptyMap()) + (file to items))
    }

    companion object {
        fun getInstance(project: Project): ByProgramModel = project.service()

        /** Published on the project bus after the project-wide answers have been refreshed. */
        @Topic.ProjectLevel
        val CHANGED: Topic<Runnable> = Topic("basedpython program model changed", Runnable::class.java)

        /** The file a `file:` URI from the server names, as its VFS path. */
        internal fun pathOf(uri: String): String? =
            runCatching { Paths.get(URI(uri)) }.getOrNull()
                ?.let { LocalFileSystem.getInstance().findFileByNioFile(it)?.path ?: it.toString() }

        private fun fileAt(uri: String): VirtualFile? =
            runCatching { Paths.get(URI(uri)) }.getOrNull()?.let(LocalFileSystem.getInstance()::findFileByNioFile)

        private const val BY_SERVER = "by"
        private const val BY_EXTENSION = "by"
        private const val ENTRY_POINT = "entryPoint"
        private const val TEST_ITEMS = "testItems"
        private const val REQUEST_TIMEOUT_MS = 2_000

        /** Every test file of the project is classified, which on a large one is the checker's work. */
        private const val PROJECT_TESTS_TIMEOUT_MS = 30_000

        /** Long enough that a save-all is one refresh. */
        private val REFRESH_DEBOUNCE = 500.milliseconds

        private const val RESTART_REASON = "basedpython run modules changed"
    }
}
