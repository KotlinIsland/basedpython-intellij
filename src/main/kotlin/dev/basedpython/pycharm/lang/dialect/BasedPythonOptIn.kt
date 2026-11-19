package dev.basedpython.pycharm.lang.dialect

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorNotifications
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.lsp.startServersForPython
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings

/**
 * Whether basedpython is used for the Python files of projects that do not name it, IDE-wide: what
 * a project that has not chosen for itself ([ProjectOptIn.UNDECIDED]) does.
 *
 * Persisted by [id]; the ids are part of the settings file format.
 */
enum class PythonProjectsDefault(val id: String, val display: String) {
    /** Ask in each project, from a banner on its Python files. The default. */
    ASK("ask", "Ask in each project"),

    /** Use it in every project that looks like Python ([ProjectKind.PYTHON]). */
    ALWAYS("always", "Always"),

    /** Never, and never ask; a project can still choose it on its own page. */
    NEVER("never", "Never"),
    ;

    companion object {
        fun fromId(id: String?): PythonProjectsDefault = entries.firstOrNull { it.id == id } ?: ASK
    }
}

/** What one project chose, for its own Python files. Persisted by [id]. */
enum class ProjectOptIn(val id: String, val display: String) {
    UNDECIDED("", "As the IDE does"),
    USE("use", "Yes"),
    SKIP("skip", "No"),
    ;

    companion object {
        fun fromId(id: String?): ProjectOptIn = entries.firstOrNull { it.id == id } ?: UNDECIDED
    }
}

/**
 * A project's own answer to "use basedpython for Python files here?", kept in the workspace file.
 *
 * The workspace file and not `.idea/basedpython.xml`, which is often committed: whether *I* want
 * basedpython in a checkout is mine, and a project that wants it for everyone says so in its own
 * config ([BasedPythonProjectDetector]'s markers, `[tool.basedpython]` among them), which `by`, the
 * CLI and every teammate's IDE read alike. `by` itself needs no marker to check plain Python — its
 * project discovery falls back to the nearest `pyproject.toml` — so the marker only ever gated the
 * plugin's own decision to start it, and this is the same decision taken by the user instead.
 */
@Service(Service.Level.PROJECT)
@State(name = "BasedPythonOptIn", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class BasedPythonProjectOptIn : PersistentStateComponent<BasedPythonProjectOptIn.State> {

    class State {
        var choice: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    var choice: ProjectOptIn
        get() = ProjectOptIn.fromId(state.choice)
        set(value) {
            state.choice = value.id
        }
}

/**
 * Whether basedpython is used for a project's Python files, where nothing in the project says so.
 *
 * In order: a basedpython marker in the project ([ProjectKind.BASEDPYTHON]) always means yes, since
 * the project itself is asking; then the project's own choice ([ProjectOptIn]); then the IDE's
 * ([PythonProjectsDefault]), whose [PythonProjectsDefault.ALWAYS] covers projects that look like
 * Python and not a Rust repository with a stray script, which is asked about on its own.
 *
 * "Used" is everything [BasedPythonProjectDetector.isBasedPythonProject] gates: `by` and `buff`
 * attaching to `.py`, the tool windows, and — in an IDE with no Python plugin, under the default
 * [PyFileHandling.AUTO] — basedpython owning the `.py` file type.
 */
object BasedPythonOptIn {

    private const val WELCOME_SHOWN_KEY: String = "dev.basedpython.pycharm.welcomeShown"

    /** Whether [project]'s Python files use basedpython, before `by` being switched off is counted. */
    fun uses(project: Project): Boolean =
        uses(BasedPythonProjectDetector.kind(project), projectChoice(project), ideDefault())

    /** [uses], as pure logic. */
    fun uses(kind: ProjectKind, choice: ProjectOptIn, default: PythonProjectsDefault): Boolean = when {
        kind == ProjectKind.BASEDPYTHON -> true
        choice == ProjectOptIn.USE -> true
        choice == ProjectOptIn.SKIP -> false
        else -> default == PythonProjectsDefault.ALWAYS && kind == ProjectKind.PYTHON
    }

    /** Whether [project] is still to be asked: nothing in it, from it or from the IDE has answered. */
    fun shouldAsk(project: Project): Boolean =
        shouldAsk(BasedPythonProjectDetector.kind(project), projectChoice(project), ideDefault())

    /** [shouldAsk], as pure logic. */
    fun shouldAsk(kind: ProjectKind, choice: ProjectOptIn, default: PythonProjectsDefault): Boolean =
        kind != ProjectKind.BASEDPYTHON &&
            choice == ProjectOptIn.UNDECIDED &&
            !uses(kind, choice, default) &&
            default != PythonProjectsDefault.NEVER

    fun projectChoice(project: Project): ProjectOptIn =
        if (project.isDisposed) ProjectOptIn.UNDECIDED else project.service<BasedPythonProjectOptIn>().choice

    fun ideDefault(): PythonProjectsDefault = BasedPythonAppSettings.getInstance().pythonProjects

    /** Stores [project]'s own choice, and tells the IDE what it changed. */
    fun choose(project: Project, choice: ProjectOptIn) {
        changing(listOf(project)) { project.service<BasedPythonProjectOptIn>().choice = choice }
    }

    /** Stores the IDE's choice, and tells every open project what it changed. */
    fun chooseForIde(default: PythonProjectsDefault) {
        changing(ProjectManager.getInstance().openProjects.toList()) {
            BasedPythonAppSettings.getInstance().pythonProjects = default
        }
    }

    /**
     * The banner's *All projects*: the IDE's choice, and this project's too where the IDE's does not
     * reach it — a project that does not look like Python — so the banner it was clicked on goes.
     */
    fun chooseForIdeFrom(project: Project) {
        val projects = ProjectManager.getInstance().openProjects.toList().let { if (project in it) it else it + project }
        changing(projects) {
            BasedPythonAppSettings.getInstance().pythonProjects = PythonProjectsDefault.ALWAYS
            if (!uses(project)) project.service<BasedPythonProjectOptIn>().choice = ProjectOptIn.USE
        }
    }

    /**
     * Runs [change], then tells the IDE about each of [projects] whose answer it changed: their
     * `.py` file types, their servers and their banners. The settings pages store their values
     * themselves, and call this around the store too.
     */
    fun changing(projects: Collection<Project>, change: () -> Unit) {
        val live = projects.filterNot { it.isDisposed }
        val before = live.associateWith { BasedPythonProjectDetector.isBasedPythonProject(it) }
        change()
        // Answering the question is meeting the plugin; the welcome would only say it again.
        markWelcomeShown()
        var fileTypes = false
        for (project in live) {
            if (project.isDisposed) continue
            EditorNotifications.getInstance(project).updateAllNotifications()
            val now = BasedPythonProjectDetector.isBasedPythonProject(project)
            if (now == before[project]) continue
            fileTypes = true
            // Nothing served the project's `.py` before, so there is nothing to restart, only to start.
            // The other way, the restart stops what served them: the platform asks each open file
            // again after the stop, and a `.py` no longer asks for a server.
            if (now) startServersForPython(project) else BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        }
        if (fileTypes) BasedPythonProjectDetector.fileTypesMayHaveChanged("basedpython used for python files changed")
    }

    /** Whether the welcome has been shown, or the question it would follow answered. */
    fun welcomeShown(): Boolean = PropertiesComponent.getInstance().getBoolean(WELCOME_SHOWN_KEY, false)

    fun markWelcomeShown() {
        PropertiesComponent.getInstance().setValue(WELCOME_SHOWN_KEY, true)
    }
}
