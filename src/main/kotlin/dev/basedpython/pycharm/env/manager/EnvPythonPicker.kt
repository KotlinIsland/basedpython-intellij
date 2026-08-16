package dev.basedpython.pycharm.env.manager

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * Choosing which Python an environment is built on.
 *
 * ### Why a popup and not a settings page
 *
 * The choice is only ever made at one moment — when the environment is created or recreated — and it
 * is made once. A persisted "preferred interpreter" setting would be a second place the answer
 * lives, permanently able to disagree with the environment that actually exists on disk; the
 * environment itself already records what it was built on, and that record is what the tool window
 * displays.
 *
 * ### What the list contains
 *
 * Interpreters already on the machine, and ones the backend can fetch. Both, because the two are the
 * same choice to the person making it: "I want 3.13" should not require first knowing whether 3.13
 * is installed. Choosing one that is not installed installs it — which is exactly why those entries
 * say so, rather than being silently equivalent.
 */
internal object EnvPythonPicker {

    /**
     * Opens the picker and acts on the choice.
     *
     * [context] decides where it appears, and is the reason this takes a [DataContext] rather than a
     * component: anchoring to the tree put the popup under the *whole tree*, which is the bottom of
     * the tool window and nowhere near the button that was pressed. A data context lets the platform
     * place it where the click was.
     *
     * The interpreter list costs a process, so it is fetched off the EDT and the popup opens when it
     * arrives. Call on the EDT.
     */
    fun choose(project: Project, context: DataContext) {
        val service = EnvService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidates = service.listPythons()
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                showPopup(project, context, entries(candidates, service.status))
            }, project.disposed)
        }
    }

    /** The picker anchored to [component], for callers that have one rather than an action event. */
    fun choose(project: Project, component: JComponent) {
        choose(project, DataManager.getInstance().getDataContext(component))
    }

    /** One row of the picker. */
    data class Entry(
        val label: String,
        /** What to pass the backend as the interpreter, or null for "let the project decide". */
        val request: String?,
        /** True when choosing this downloads an interpreter first. */
        val needsInstall: Boolean,
    )

    /**
     * The rows, in the order they should be offered.
     *
     * The first is always "whatever the project asks for" — the backend reading the project's own
     * `requires-python` is the correct answer whenever the project states one, and making the user
     * pick a specific version instead is how a project ends up pinned to something its own manifest
     * does not ask for.
     *
     * Then installed interpreters, newest first, then downloadable ones. Versions are collapsed to
     * their feature version (`3.12`, not `3.12.8`): that is what a person means by a Python version
     * and what every backend accepts as a request, and a list of forty patch releases is not a
     * choice anyone wants to make.
     */
    fun entries(candidates: List<PythonCandidate>, status: EnvStatus): List<Entry> {
        val current = status.environment?.pythonVersion
            ?.split('.')?.take(2)?.joinToString(".")

        val installed = LinkedHashSet<String>()
        val downloadable = LinkedHashSet<String>()
        for (candidate in candidates) {
            // Only CPython is offered. The alternatives resolve differently and would need their own
            // request syntax; a backend that wants to offer them can be given a richer entry type.
            if (!candidate.implementation.equals("cpython", ignoreCase = true)) continue
            // Pre-releases are excluded: `3.15.0rc1` collapses to a `3.15` that is not generally
            // available, and offering it beside `3.13` reads as an ordinary choice rather than as
            // opting into a release candidate.
            if (candidate.version.any { it.isLetter() }) continue
            if (candidate.isInstalled) installed += candidate.featureVersion else downloadable += candidate.featureVersion
        }
        downloadable -= installed

        val rows = mutableListOf(
            Entry(BasedPythonBundle.message("env.python.fromProject"), null, needsInstall = false),
        )
        installed.sortedWith(VERSION_ORDER).forEach {
            val label = if (it == current) {
                BasedPythonBundle.message("env.python.installed.current", it)
            } else {
                BasedPythonBundle.message("env.python.installed", it)
            }
            rows += Entry(label, it, needsInstall = false)
        }
        downloadable.sortedWith(VERSION_ORDER).forEach {
            rows += Entry(BasedPythonBundle.message("env.python.download", it), it, needsInstall = true)
        }
        return rows
    }

    /** Newest first, comparing the numeric parts so `3.9` sorts below `3.10` rather than above it. */
    private val VERSION_ORDER: Comparator<String> = Comparator { a, b ->
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (right.getOrElse(i) { 0 }).compareTo(left.getOrElse(i) { 0 })
            if (diff != 0) return@Comparator diff
        }
        0
    }

    private fun showPopup(project: Project, context: DataContext, entries: List<Entry>) {
        if (entries.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(entries.map { it.label })
            .setTitle(BasedPythonBundle.message("env.python.title"))
            .setItemChosenCallback { label ->
                entries.firstOrNull { it.label == label }?.let { apply(project, it) }
            }
            .createPopup()
            .showInBestPositionFor(context)
    }

    /**
     * Acts on a chosen entry.
     *
     * Recreating an existing environment is confirmed first: it discards everything installed into
     * it, which for a project with a large dependency tree is minutes of downloads, and there is no
     * undo.
     */
    private fun apply(project: Project, entry: Entry) {
        val status = EnvService.getInstance(project).status
        if (status.environment != null) {
            val confirmed = EnvOperations.confirm(
                project,
                BasedPythonBundle.message("env.python.recreate.title"),
                BasedPythonBundle.message(
                    "env.python.recreate.message",
                    entry.request ?: BasedPythonBundle.message("env.python.fromProject"),
                    status.environment.root.toString(),
                ),
            )
            if (!confirmed) return
        }
        when {
            entry.needsInstall && entry.request != null ->
                EnvOperations.installPythonAndCreate(project, entry.request)
            else -> EnvOperations.createEnvironment(project, entry.request)
        }
    }
}
