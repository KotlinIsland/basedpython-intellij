package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import dev.basedpython.pycharm.settings.BasedPythonSettings
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
 * — and the verdict is cached until the VFS structure changes, because [isBasedPythonProject] sits
 * on the file-type resolution hot path.
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

    /** Project user data key holding the last scan and the VFS state it was taken at. */
    private val CACHE = Key.create<Cached>("basedpython.projectKind")

    private class Cached(val stamp: Long, val kind: ProjectKind)

    /**
     * The cached verdict for [project], rescanned when the VFS structure changes.
     *
     * A plain stamped cache rather than `CachedValuesManager`: this is called from a
     * `FileTypeOverrider`, which runs early and often, and has no business pulling in the PSI
     * caching machinery to answer a question about one directory listing.
     */
    fun kind(project: Project): ProjectKind {
        val stamp = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        project.getUserData(CACHE)?.let { if (it.stamp == stamp) return it.kind }
        val kind = scan(project)
        project.putUserData(CACHE, Cached(stamp, kind))
        return kind
    }

    private fun scan(project: Project): ProjectKind {
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
