package dev.basedpython.pycharm.env.manager

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.EditorNotifications
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
    fun createEnvironment(project: Project, python: String?) {
        val backend = EnvService.getInstance(project).status.backend ?: return
        runInBackground(project, BasedPythonBundle.message("env.progress.creating")) { indicator ->
            if (!ensureTool(project, backend, indicator)) return@runInBackground
            if (!runBlockingOp(project, backend, EnvOp.Create(python))) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.syncing")
            runBlockingOp(project, backend, EnvOp.Sync)
        }
    }

    /**
     * Recreates the environment on [python], replacing whatever is there.
     *
     * "Change the Python version" is not an operation any of these tools has — an environment is
     * built against one interpreter and cannot be moved to another — so it is spelled out here as
     * what it actually is, and the caller confirms it with the user first.
     */
    fun changePython(project: Project, python: String) = createEnvironment(project, python)

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
        target: EnvDependencyTarget = EnvDependencyTarget.Main,
    ) {
        if (requirements.isEmpty()) return
        simple(
            project,
            EnvOp.Add(requirements, target),
            BasedPythonBundle.message("env.progress.adding", requirements.joinToString(", ")),
        )
    }

    /**
     * Removes requirements, each from the group it is declared in.
     *
     * A map rather than a list because a selection can span groups, and removing `pytest` from
     * `dev` and `httpx` from the main list is two edits to two lists that no single command
     * expresses. They run in sequence in one background task, and the first failure stops the rest —
     * continuing past a `uv remove` that failed would leave the project half-edited with only a
     * notification to say which half.
     */
    fun remove(project: Project, byTarget: Map<EnvDependencyTarget, List<String>>) {
        val work = byTarget.filterValues { it.isNotEmpty() }
        if (work.isEmpty()) return
        val backend = EnvService.getInstance(project).status.backend ?: return
        val all = work.values.flatten().joinToString(", ")
        runInBackground(project, BasedPythonBundle.message("env.progress.removing", all)) { indicator ->
            for ((target, names) in work) {
                indicator.text = BasedPythonBundle.message("env.progress.removing", names.joinToString(", "))
                if (!runBlockingOp(project, backend, EnvOp.Remove(names, target))) return@runInBackground
            }
        }
    }

    /** Installs an interpreter the machine does not have, then builds the environment on it. */
    fun installPythonAndCreate(project: Project, version: String) {
        val backend = EnvService.getInstance(project).status.backend ?: return
        runInBackground(project, BasedPythonBundle.message("env.progress.installingPython", version)) { indicator ->
            if (!runBlockingOp(project, backend, EnvOp.InstallPython(version))) return@runInBackground
            indicator.text = BasedPythonBundle.message("env.progress.creating")
            if (!runBlockingOp(project, backend, EnvOp.Create(version))) return@runInBackground
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
    private fun runInBackground(project: Project, title: String, body: (ProgressIndicator) -> Unit) {
        val service = EnvService.getInstance(project)
        val backend = service.status.backend
        val root = service.status.projectRoot

        // Before the command starts, and on the thread the action was invoked from: the command is
        // about to read these files off disk, so anything still sitting unsaved in an editor has to
        // reach disk first or it is silently overwritten.
        if (backend != null && root != null) EnvFiles.saveBeforeOperation(project, backend, root)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            /**
             * The whole gesture is one busy stretch, not one per step: the toolbar disables what
             * must not run twice, and a *Sync* button that re-enables itself between the create and
             * the sync of a single *Set Up* is an invitation to start a second uv against the same
             * environment.
             */
            override fun run(indicator: ProgressIndicator) {
                try {
                    service.busyWhile { body(indicator) }
                } finally {
                    // In a finally, and off the EDT, because a cancelled or failed command has
                    // usually already written something — a `uv add` that failed to resolve has
                    // still edited `pyproject.toml` — and the editor must not be left showing the
                    // file as it was before.
                    if (backend != null && root != null) EnvFiles.refreshAfterOperation(backend, root)
                }
            }

            /**
             * Runs whether the task succeeded, failed or was cancelled — which is the point. A
             * cancelled `uv sync` has usually already installed some of what it resolved, so the
             * view must be re-read rather than left showing the state from before.
             */
            override fun onFinished() {
                service.refresh()
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
    private fun runBlockingOp(project: Project, backend: EnvBackend, op: EnvOp): Boolean {
        val root = EnvService.getInstance(project).status.projectRoot ?: return false
        val command = backend.command(op) ?: return false
        val result = EnvRunner.run(project, backend, command, root)
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
            val manager = LspServerManager.getInstance(project)
            manager.stopAndRestartIfNeeded(ByLspServerSupportProvider::class.java)
            manager.stopAndRestartIfNeeded(BuffLspServerSupportProvider::class.java)
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
