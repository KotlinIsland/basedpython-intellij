package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Treats plain `.py` files inside a basedpython project as basedpython source (FEATURES.md §16).
 *
 * Rationale: an IDE with no Python plugin opens a `.py` file as plain text — no highlighting, no
 * LSP. Re-typing it to [BasedPythonFileType] gives it basedpython highlighting and routes it
 * through the `by` / `buff` servers (whose supported extensions already include `py`).
 *
 * In PyCharm that same move is a downgrade: it takes `.py` away from a plugin that understands
 * Python properly. So the default ([PyFileHandling.AUTO]) only claims `.py` when nothing else
 * provides the Python language, and [PyFileHandling.NEVER] / [PyFileHandling.ALWAYS] let the user
 * override either way. Either way the `by` server still attaches to `.py` — see
 * `ByLspServerSupportProvider`. This is only about who owns the file type.
 *
 * Scope is otherwise narrow:
 *   - Only `.py` is overridden. `.by` is handled by the file-type registration itself, and `.pyi`
 *     stub files are left alone.
 *   - Only projects that actually carry a basedpython marker (see [BasedPythonProjectDetector])
 *     are affected; a plain Python project keeps its `.py` files.
 *
 * Resolving a `Project` from a `VirtualFile` here is best-effort via
 * [ProjectLocator.guessProjectForFile]; a null project means "don't override".
 */
class BasedPythonFileTypeOverrider : FileTypeOverrider {

    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        // Cheap extension gate first — bail before touching project services.
        if (!isOverridableExtension(file.extension)) return null
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        return decide(
            extension = file.extension,
            isBasedPythonProject = BasedPythonProjectDetector.isBasedPythonProject(project),
            handling = BasedPythonSettings.getInstance(project).pyFileHandling,
            pythonLanguageAvailable = PyFileHandling.isPythonLanguageAvailable(),
        )
    }

    companion object {
        /** Extension we are willing to override (case-insensitive); only plain `.py`. */
        const val OVERRIDABLE_EXTENSION: String = "py"

        /** True when [extension] is the one extension this overrider acts on. */
        fun isOverridableExtension(extension: String?): Boolean =
            extension?.lowercase() == OVERRIDABLE_EXTENSION

        /**
         * Pure decision used by [getOverriddenFileType], extracted for unit testing.
         *
         * @return [BasedPythonFileType.INSTANCE] when the file should be re-typed, else null
         *   (normal handling applies).
         */
        fun decide(
            extension: String?,
            isBasedPythonProject: Boolean,
            handling: PyFileHandling,
            pythonLanguageAvailable: Boolean,
        ): FileType? {
            if (!isOverridableExtension(extension)) return null
            if (!isBasedPythonProject) return null
            val claim = when (handling) {
                PyFileHandling.ALWAYS -> true
                PyFileHandling.NEVER -> false
                PyFileHandling.AUTO -> !pythonLanguageAvailable
            }
            return if (claim) BasedPythonFileType.INSTANCE else null
        }
    }
}
