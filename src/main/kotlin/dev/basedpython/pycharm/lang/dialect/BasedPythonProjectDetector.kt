package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.ex.FileTypeManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.settings.BasedPythonSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What kind of project this is, from cheapest evidence available.
 *
 * Ordered: every basedpython project is also a Python project.
 */
enum class ProjectKind {
    /** Nothing Python-shaped at the project base. */
    OTHER,

    /** Python, but nothing says basedpython. */
    PYTHON,

    /** Carries a basedpython marker. */
    BASEDPYTHON,
}

/**
 * Cheap, side-effect-free detection of what a [Project] is (FEATURES.md §16).
 *
 * Two questions, deliberately separated:
 *
 *  - [isBasedPythonProject] gates anything that claims files or speaks to `by`: re-typing `.py`,
 *    starting the language servers for `.py`, the welcome notification.
 *  - [isPythonProject] gates the merely-visible: the status bar widget. A Rust project with one
 *    stray script should not grow a basedpython widget, and it definitely should not spawn `by`.
 *
 * A bare `pyproject.toml` used to be enough to call a project basedpython, which meant *every*
 * Python project got its `.py` files re-typed and a language server spawned. It now only counts
 * when the manifest actually mentions basedpython.
 *
 * The scan is a single, bounded listing of the project base directory — no indexing, no deep walks
 * — and the verdict is cached until something it read changes, because [isBasedPythonProject] sits
 * on the file-type resolution hot path. See [BasedPythonProjectKindCache].
 */
object BasedPythonProjectDetector {

    /** Manifests that mark a project as basedpython on their own. */
    private val BASEDPYTHON_MARKER_FILES = setOf("api.lock", "basedpython.toml")

    /** Extensions whose presence at the base marks a basedpython project. */
    private val BASEDPYTHON_EXTENSIONS = setOf("by", "byi")

    /** Manifests and layout markers that mark a project as Python. */
    private val PYTHON_MARKER_FILES = setOf(
        "pyproject.toml",
        "setup.py",
        "setup.cfg",
        "requirements.txt",
        "Pipfile",
        "poetry.lock",
        "uv.lock",
        "tox.ini",
        "conftest.py",
        ".venv",
        "ty.toml",
    )

    /** Extensions whose presence at the base marks a Python project. */
    private val PYTHON_EXTENSIONS = setOf("py", "pyi", "pyx")

    /** Only this much of `pyproject.toml` is read; the interesting tables are near the top. */
    private const val PYPROJECT_READ_LIMIT = 64 * 1024

    /**
     * True when [project] should be treated as basedpython: the `by` server is enabled and the base
     * directory carries a basedpython marker.
     */
    fun isBasedPythonProject(project: Project): Boolean =
        BasedPythonSettings.getInstance(project).byEnabled && kind(project) == ProjectKind.BASEDPYTHON

    /**
     * True when [project] looks like Python at all, basedpython or otherwise.
     *
     * Not gated on `byEnabled`: this answers "would a basedpython user expect to see us here",
     * which stays true while the server is switched off.
     */
    fun isPythonProject(project: Project): Boolean = kind(project) != ProjectKind.OTHER


    /**
     * The cached verdict for [project], rescanned once what it was read from changes.
     *
     * A plain cache rather than `CachedValuesManager`: this is called from a `FileTypeOverrider`,
     * which runs early and often, and has no business pulling in the PSI caching machinery to
     * answer a question about one directory listing.
     */
    fun kind(project: Project): ProjectKind {
        // A disposed project has no services; the scan still answers, it just answers uncached.
        if (project.isDisposed) return scan(project)
        return project.service<BasedPythonProjectKindCache>().kind { scan(project) }
    }

    /**
     * Tells the platform that which `.py` files are basedpython's may have changed, so files it has
     * already typed are typed again.
     *
     * In a write action, and not inline. A file-type change fires a roots change, and
     * `ProjectRootManagerImpl.fireBeforeRootsChanged` asserts write access — while a settings page's
     * `apply` runs under a write-*intent* read action, which is a weaker lock and not the same
     * thing: `SettingsNonModalDialog.applyWithWriteIntent` is what calls it. Pressing OK with the
     * `.py` handling changed therefore threw, and the settings after that point were never applied.
     */
    fun fileTypesMayHaveChanged(reason: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                FileTypeManagerEx.getInstanceEx().makeFileTypesChange(reason) {}
            }
        }
    }

    internal fun scan(project: Project): ProjectKind {
        val base = basePath(project) ?: return ProjectKind.OTHER
        val names = baseEntryNames(base) ?: return ProjectKind.OTHER
        val pyproject =
            if ("pyproject.toml" in names) readHead(base.resolve("pyproject.toml")) else null
        return classify(names, pyproject)
    }

    /**
     * The verdict for a base directory listing, as pure logic.
     *
     * @param names entry names directly inside the project base directory
     * @param pyprojectText the head of `pyproject.toml`, or null when there is none
     */
    fun classify(names: Set<String>, pyprojectText: String?): ProjectKind {
        val hasBasedPythonMarker =
            names.any { it in BASEDPYTHON_MARKER_FILES || extensionOf(it) in BASEDPYTHON_EXTENSIONS } ||
                (pyprojectText != null && mentionsBasedPython(pyprojectText))
        if (hasBasedPythonMarker) return ProjectKind.BASEDPYTHON

        val hasPythonMarker =
            names.any { it in PYTHON_MARKER_FILES || extensionOf(it) in PYTHON_EXTENSIONS }
        return if (hasPythonMarker) ProjectKind.PYTHON else ProjectKind.OTHER
    }

    /**
     * True when a `pyproject.toml` opts into basedpython — either a `[tool.basedpython]` table or
     * `basedpython` among the requirements.
     *
     * Matched textually rather than by parsing TOML: this runs before anything is indexed, the
     * answer only has to be right about the word appearing, and a false positive costs a language
     * server the user was already asking for.
     */
    private fun mentionsBasedPython(text: String): Boolean = text.contains("basedpython")

    /** Lowercased extension of [name], or "" when it has none. */
    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    private fun basePath(project: Project): Path? =
        project.basePath?.let {
            try {
                Paths.get(it)
            } catch (_: RuntimeException) {
                null
            }
        }

    /** Names of the entries directly inside [base], or null when it cannot be listed. */
    private fun baseEntryNames(base: Path): Set<String>? =
        try {
            Files.newDirectoryStream(base).use { stream ->
                stream.mapTo(mutableSetOf()) { it.fileName.toString() }
            }
        } catch (_: Exception) {
            null
        }

    /** The first [PYPROJECT_READ_LIMIT] bytes of [file], or null when it cannot be read. */
    private fun readHead(file: Path): String? =
        try {
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(PYPROJECT_READ_LIMIT)
                val read = input.readNBytes(buffer, 0, buffer.size)
                String(buffer, 0, read, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
}

/**
 * The last verdict [BasedPythonProjectDetector.kind] reached, kept until something it was read from
 * changes.
 *
 * What the verdict is read from is the listing of the project base directory and the content of
 * its `pyproject.toml`, so those are what drop it: a VFS event that creates, deletes, moves or
 * renames an entry directly in the base directory, or changes `pyproject.toml`. It used to be kept
 * only until *any* structural change anywhere in the VFS — so every file created in any open
 * project cost a directory listing and a 64 KB read inside file-type resolution.
 *
 * When a dropped verdict comes back different, the files the platform has already typed are typed
 * again ([BasedPythonProjectDetector.fileTypesMayHaveChanged]); before that, an edit to
 * `pyproject.toml` that made a project basedpython left its open `.py` files as they were.
 *
 * A project service rather than `Project.putUserData`, which is where this lived: user data set on
 * a `Project` is never cleared when a plugin unloads, so a value of this plugin's own class left
 * there keeps the plugin's classloader alive, and disabling the plugin reports *"didn't unload
 * fully"*. A service is disposed with the plugin, and its VFS subscription with it.
 */
@Service(Service.Level.PROJECT)
internal class BasedPythonProjectKindCache(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    @Volatile
    private var cached: ProjectKind? = null

    /** Bumped whenever the verdict is dropped, so a scan that raced the drop does not store its answer. */
    private val generation = AtomicLong()

    private val watching = AtomicBoolean(false)

    /** How many scans this cache has run; for tests to see that an unrelated change costs none. */
    @Volatile
    var scans: Int = 0
        private set

    /** The remembered verdict, or [scan]'s, remembered. */
    fun kind(scan: () -> ProjectKind): ProjectKind {
        cached?.let { return it }
        watch()
        val at = generation.get()
        scans++
        val kind = scan()
        if (generation.get() == at) cached = kind
        return kind
    }

    private fun watch() {
        if (!watching.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val base = project.basePath?.let(FileUtil::toSystemIndependentName) ?: return
                if (events.any { affectsVerdict(it, base) }) invalidate()
            }
        })
        // The VFS only reports changes among children it has loaded. The base directory's are
        // loaded in any open project, but not necessarily yet when this first runs.
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.children
    }

    private fun invalidate() {
        val previous = cached
        generation.incrementAndGet()
        cached = null
        if (previous == null) return
        scope.launch(Dispatchers.IO) {
            val now = kind { BasedPythonProjectDetector.scan(project) }
            if ((now == ProjectKind.BASEDPYTHON) != (previous == ProjectKind.BASEDPYTHON)) {
                edtWriteAction {
                    FileTypeManagerEx.getInstanceEx().makeFileTypesChange("basedpython project markers changed") {}
                }
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        /** Whether [event] changes the base directory listing, or the `pyproject.toml` in it. */
        fun affectsVerdict(event: VFileEvent, base: String): Boolean {
            fun inBase(path: String?) = path != null && (path == base || parentOf(path) == base)
            return when (event) {
                is VFileContentChangeEvent -> event.path == "$base/pyproject.toml"
                is VFilePropertyChangeEvent ->
                    event.propertyName == VirtualFile.PROP_NAME && (inBase(event.path) || inBase(event.oldPath))
                is VFileMoveEvent -> inBase(event.path) || inBase(event.oldPath)
                is VFileCopyEvent -> inBase(event.path)
                else -> event.path.let { it == base || parentOf(it) == base }
            }
        }

        private fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}
