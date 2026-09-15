package dev.basedpython.pycharm.env.manager

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.EditorNotifications
import dev.basedpython.pycharm.env.modules.UvWorkspace
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.ui.log.BasedPythonLogNotifications
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The gestures the UI offers, and what has to happen around them.
 *
 * Every operation here is started by a user action — nothing on this path runs because a project was
 * opened. That is the plugin's standing rule for uv (see
 * [dev.basedpython.pycharm.env.ByEnvironmentKind.UV]) and it is what makes an environment manager
 * that "just works" acceptable rather than alarming: the plugin will tell you what is wrong and fix
 * it in one click, and it will not create environments or download interpreters behind your back.
 *
 * ### The part that is easy to leave out
 *
 * [afterEnvironmentChanged]. Creating or syncing an environment is the moment `by` and `buff` start
 * or stop resolving, and every consumer of that answer cached it: the language servers hold a
 * binary path from startup, and the "by not found" banner was decided when the file was opened. An
 * operation that does not tell them is the difference between "installed basedpython and everything
 * lit up" and "installed basedpython, nothing changed, restarted the IDE".
 */
internal object EnvOperations {

    /**
     * Do whatever this project needs to reach a working environment, in one gesture.
     *
     * The three states are steps of the same job — install the tool, create the environment, sync
     * it — so a project two steps from working takes two steps, not two visits to the tool window.
     * [EnvHealth.READY] still syncs: the button is only offered when something is wrong, and reaching
     * it in a READY state means the state changed underneath, where doing the harmless idempotent
     * thing beats doing nothing and looking broken.
     */
    fun setUp(project: Project) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        runInBackground(project, BasedPythonBundle.message("env.progress.settingUp")) { indicator ->
            if (!ensureTool(project, backend, indicator)) return@runInBackground
            val status = service.status
            if (status.environment == null) {
                indicator.text = BasedPythonBundle.message("env.progress.creating")
                if (!runBlockingOp(project, backend, EnvOp.Create())) return@runInBackground
            }
            indicator.text = BasedPythonBundle.message("env.progress.syncing")
            runBlockingOp(project, backend, EnvOp.Sync)
        }
    }

    /** Downloads and installs the backend's tool. */
    fun installTool(project: Project) {
        val backend = EnvService.getInstance(project).status.backend ?: return
        runInBackground(project, BasedPythonBundle.message("env.progress.installing", backend.displayName)) {
            ensureTool(project, backend, it)
        }
    }

    /**
     * Creates the environment, letting the user choose the interpreter first.
     *
     * The picker is offered rather than imposed: [EnvOp.Create] with no interpreter lets the backend
     * apply the project's own `requires-python`, which is the right answer whenever the project
     * states one — and the reason the picker's first entry is "whatever the project asks for".
     */
    /**
     * Creates the environment, replacing one that is already there.
     *
     * Whether this is a create or a recreate is read from the current state rather than asked of the
     * caller, because it is not really a choice: if an environment exists, the backend has to be told
     * to replace it or it refuses to do anything at all. The caller's job is to have confirmed with
     * the user first, which [EnvPythonPicker] does — this is where everything installed goes away.
     */
    fun createEnvironment(project: Project, python: String?) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val replacing = service.status.environment != null
        runInBackground(project, BasedPythonBundle.message("env.progress.creating")) { indicator ->
            if (!ensureTool(project, backend, indicator)) return@runInBackground
            if (!runBlockingOp(project, backend, EnvOp.Create(python, replacing))) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.syncing")
            runBlockingOp(project, backend, EnvOp.Sync)
        }
    }

    fun sync(project: Project) = simple(project, EnvOp.Sync, BasedPythonBundle.message("env.progress.syncing"))

    fun lock(project: Project) = simple(project, EnvOp.Lock, BasedPythonBundle.message("env.progress.locking"))

    /** Re-resolves past the lock's pins, then installs the result — an upgrade is both halves. */
    fun upgrade(project: Project) {
        val backend = EnvService.getInstance(project).status.backend ?: return
        runInBackground(project, BasedPythonBundle.message("env.progress.upgrading")) { indicator ->
            if (!runBlockingOp(project, backend, EnvOp.Upgrade)) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.syncing")
            runBlockingOp(project, backend, EnvOp.Sync)
        }
    }

    fun add(
        project: Project,
        requirements: List<String>,
        list: EnvDependencyList = EnvDependencyList(EnvDependencyTarget.Main),
    ) {
        if (requirements.isEmpty()) return
        val backend = EnvService.getInstance(project).status.backend ?: return
        val op = EnvOp.Add(requirements, list.target, list.module)
        runInBackground(
            project,
            BasedPythonBundle.message("env.progress.adding", requirements.joinToString(", ")),
            manifestsOf(project, listOf(list)),
        ) { indicator ->
            // The "by not found" banner offers this on a machine that may not have uv yet.
            if (!ensureTool(project, backend, indicator)) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.adding", requirements.joinToString(", "))
            runBlockingOp(project, backend, op)
        }
    }

    /**
     * The manifests these lists live in, for the ones the backend's own list cannot name.
     *
     * `uv add --package sub` rewrites `sub/pyproject.toml`, which [EnvBackend.managedFiles] does not
     * and cannot know about — it names files relative to the project root, and a module's manifest is
     * neither at the root nor discoverable without the layout. Without this the file is not flushed
     * before the command reads it and not refreshed after it writes: an unsaved editor buffer for a
     * member's manifest survives the command and the user's next save deletes what uv just wrote.
     *
     * The root's own manifest is left out because [EnvFiles.saveBeforeOperation] already covers it.
     */
    private fun manifestsOf(project: Project, lists: Collection<EnvDependencyList>): List<java.nio.file.Path> =
        manifestsOfModules(project, lists.mapNotNull { it.module })

    /**
     * The manifests of the modules called [names], for a gesture that runs `uv add --package` or
     * `uv remove --package` against them — see [manifestsOf] for why they have to be named.
     *
     * Names the layout does not know are skipped: a module that does not exist has no manifest for
     * anything to rewrite.
     */
    fun manifestsOfModules(project: Project, names: Collection<String>): List<java.nio.file.Path> {
        val layout = EnvService.getInstance(project).status.modules ?: return emptyList()
        return names.distinct()
            .mapNotNull { layout.byName(it) }
            .filterNot { it.isRoot }
            .map { it.root.resolve(UvWorkspace.MANIFEST) }
            .distinct()
    }

    /**
     * Removes requirements, each from the list it is declared in.
     *
     * A map rather than a list because a selection can span lists, and removing `pytest` from `dev`
     * and `httpx` from the main list is two edits to two lists that no single command expresses.
     * They run in sequence in one background task, and the first failure stops the rest —
     * continuing past a `uv remove` that failed would leave the project half-edited with only a
     * notification to say which half.
     *
     * Keyed by [EnvDependencyList] rather than by target, so a workspace's manifests stay apart: the
     * module is what becomes `--package`, and without it every removal edits the root's manifest.
     */
    fun remove(project: Project, byList: Map<EnvDependencyList, List<String>>) {
        val work = byList.filterValues { it.isNotEmpty() }
        if (work.isEmpty()) return
        val backend = EnvService.getInstance(project).status.backend ?: return
        val all = work.values.flatten().joinToString(", ")
        val title = BasedPythonBundle.message("env.progress.removing", all)
        runInBackground(project, title, manifestsOf(project, work.keys)) { indicator ->
            for ((list, names) in work) {
                indicator.text = BasedPythonBundle.message("env.progress.removing", names.joinToString(", "))
                val op = EnvOp.Remove(names, list.target, list.module)
                if (!runBlockingOp(project, backend, op)) return@runInBackground
            }
        }
    }

    /** Installs an interpreter the machine does not have, then builds the environment on it. */
    fun installPythonAndCreate(project: Project, version: String) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend ?: return
        val replacing = service.status.environment != null
        runInBackground(project, BasedPythonBundle.message("env.progress.installingPython", version)) { indicator ->
            if (!runBlockingOp(project, backend, EnvOp.InstallPython(version))) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.creating")
            if (!runBlockingOp(project, backend, EnvOp.Create(version, replacing))) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.syncing")
            runBlockingOp(project, backend, EnvOp.Sync)
        }
    }

    // ---- plumbing ----------------------------------------------------------

    private fun simple(project: Project, op: EnvOp, title: String) {
        val backend = EnvService.getInstance(project).status.backend ?: return
        runInBackground(project, title) { runBlockingOp(project, backend, op) }
    }

    /**
     * Runs [body] in a cancellable background task, then refreshes and re-notifies everything that
     * cached an answer about the environment.
     */
    fun runInBackground(
        project: Project,
        title: String,
        /**
         * Manifests outside the project root this gesture touches — see [EnvFiles.saveBeforeOperation]
         * — and directories whose whole contents the gesture creates, which are re-read recursively
         * afterwards.
         */
        extraFiles: List<java.nio.file.Path> = emptyList(),
        body: (ProgressIndicator) -> Unit,
    ) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend
        val root = service.status.projectRoot

        // Claimed here, when the gesture is made, rather than when the task starts: every entry
        // point — the tool window, the banner, the menu, the modules page — comes through this one
        // function, so this is the one place that can promise a second uv never races the first.
        if (!service.tryBeginOperation()) {
            notify(project, title, BasedPythonBundle.message("env.busy"), NotificationType.WARNING)
            return
        }
        val ended = java.util.concurrent.atomic.AtomicBoolean(false)
        val end = { if (ended.compareAndSet(false, true)) service.endOperation() }

        try {
            // Before the command starts, and on the thread the action was invoked from: the command
            // is about to read these files off disk, so anything still sitting unsaved in an editor
            // has to reach disk first or it is silently overwritten.
            if (backend != null && root != null) EnvFiles.saveBeforeOperation(project, backend, root, extraFiles)
        } catch (e: Throwable) {
            end()
            throw e
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            /**
             * The whole gesture is one busy stretch, not one per step: the toolbar disables what
             * must not run twice, and a *Sync* button that re-enables itself between the create and
             * the sync of a single *Set Up* is an invitation to start a second uv against the same
             * environment.
             */
            override fun run(indicator: ProgressIndicator) {
                try {
                    // The busy stretch ends with a scan of the environment — however the body
                    // ended, since a cancelled `uv sync` has usually already installed some of what
                    // it resolved.
                    try {
                        body(indicator)
                    } finally {
                        // In a finally, and off the EDT, because a cancelled or failed command has
                        // usually already written something — a `uv add` that failed to resolve has
                        // still edited `pyproject.toml` — and the editor must not be left showing
                        // the file as it was before. Before the operation ends, so the watcher's
                        // request for these very changes is recognised as served by the scan the
                        // operation ends with rather than scanned for again.
                        if (backend != null && root != null) {
                            EnvFiles.refreshAfterOperation(backend, root, extraFiles)
                        }
                    }
                } finally {
                    end()
                    // However the gesture ended, nothing is still installing.
                    service.clearProgress()
                }
            }

            /**
             * Runs whether the task succeeded, failed or was cancelled — which is the point. Also
             * ends the operation for a task cancelled before [run] was ever called.
             */
            override fun onFinished() {
                end()
                afterEnvironmentChanged(project)
            }
        })
    }

    /**
     * Runs one op to completion on the calling (background) thread; true when it succeeded.
     *
     * Blocking, and goes straight to [EnvRunner]: the multi-step operations above have to know
     * whether step one worked before starting step two, and refreshing between every step would be
     * three package-list processes for one gesture. The single refresh happens in [runInBackground]
     * when the whole gesture is over.
     */
    fun runBlockingOp(project: Project, backend: EnvBackend, op: EnvOp): Boolean {
        val root = EnvService.getInstance(project).status.projectRoot ?: return false
        val command = backend.command(op) ?: return false
        val service = EnvService.getInstance(project)
        // Removals are announced only in the trailing `-` block, so the rows would sit still until
        // they vanished; naming them up front is what makes Remove show anything at all.
        if (op is EnvOp.Remove) service.markStarting(op.packages, EnvPackageActivity.REMOVING)
        val result = EnvRunner.run(project, backend, command, root, service::onOperationOutput)
        if (!result.isSuccess) {
            notify(
                project,
                BasedPythonBundle.message("env.failed.title", command.describe(backend.executableName)),
                result.failureMessage(),
                NotificationType.ERROR,
            )
        }
        return result.isSuccess
    }

    /** Installs the tool if it is missing; true when a tool is available afterwards. */
    private fun ensureTool(project: Project, backend: EnvBackend, indicator: ProgressIndicator): Boolean {
        if (EnvTools.isInstalled(backend)) return true
        return when (val outcome = EnvToolInstall.install(backend, indicator)) {
            is EnvToolInstall.Outcome.Installed -> {
                notify(
                    project,
                    BasedPythonBundle.message("env.tool.installed.title", backend.displayName),
                    BasedPythonBundle.message("env.tool.installed.text", outcome.path.toString()),
                    NotificationType.INFORMATION,
                )
                true
            }

            EnvToolInstall.Outcome.Unsupported -> {
                notify(
                    project,
                    BasedPythonBundle.message("env.tool.failed.title", backend.displayName),
                    BasedPythonBundle.message("env.tool.unsupported", backend.displayName),
                    NotificationType.WARNING,
                )
                false
            }

            is EnvToolInstall.Outcome.Failed -> {
                notify(
                    project,
                    BasedPythonBundle.message("env.tool.failed.title", backend.displayName),
                    outcome.message,
                    NotificationType.ERROR,
                )
                false
            }
        }
    }

    /**
     * Tells the rest of the plugin that the environment is not what it was.
     *
     * The language servers are restarted rather than asked to re-resolve, because a running server
     * is a process launched from a binary path that may no longer be the right one — a `by` that has
     * just been installed into a freshly created `.venv` is a different executable from the one on
     * `PATH` the server may have started from. Editor notifications are recomputed for the same
     * reason: the "by binary not found" banner is a cached verdict, and the whole point of the
     * install button on it is that it goes away by itself.
     */
    fun afterEnvironmentChanged(project: Project) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val manager = LspClientManager.getInstance(project)
            manager.stopAndRestartClientsIfNeeded(ByLspServerSupportProvider::class.java)
            manager.stopAndRestartClientsIfNeeded(BuffLspServerSupportProvider::class.java)
            EditorNotifications.getInstance(project).updateAllNotifications()
        }, project.disposed)
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        BasedPythonLogNotifications.create(project, title, content, type).notify(project)
    }

    /** Asks for a confirmation before an operation that discards work. Must be called on the EDT. */
    fun confirm(project: Project, title: String, message: String): Boolean =
        Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES
}
