package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
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
 *   - Only files of the project. A [LightVirtualFile] is decided by the file it is a copy of (a
 *     diff side, a revision, a preview), and one that is a copy of no file is left as its maker
 *     typed it: PythonCore parses expressions into a `dummy.py` of its own typed as Python
 *     (`PyExpressionCodeFragmentImpl`), and typing that basedpython made its view provider refuse
 *     the Python PSI built in it, an `AssertionError` from PythonCore's type evaluation on every
 *     such parse. The platform asks overriders before the type a file's maker assigned, so it is
 *     this overrider that has to stand back. Python source the plugin makes in memory itself says
 *     its type through [pyFileTypeIn].
 *
 * Resolving a `Project` from a `VirtualFile` here is best-effort via
 * [ProjectLocator.guessProjectForFile]; a null project means "don't override".
 */
class BasedPythonFileTypeOverrider : FileTypeOverrider {

    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        // Cheap extension gate first — bail before touching project services.
        if (!isOverridableExtension(file.extension)) return null
        val decided = if (file is LightVirtualFile) file.originalFile ?: return null else file
        val project = ProjectLocator.getInstance().guessProjectForFile(decided) ?: return null
        return decideFor(project, decided.extension)
    }

    companion object {
        /** Extension we are willing to override (case-insensitive); only plain `.py`. */
        const val OVERRIDABLE_EXTENSION: String = "py"

        /**
         * The type a `.py` file of [project] has: basedpython where this overrider claims `.py`
         * there, otherwise whatever the IDE gives the extension. For Python source the plugin makes
         * in memory for the project, such as a transpiled module: a file of nobody's on disk is not
         * one this overrider decides about, so its maker has to say.
         */
        fun pyFileTypeIn(project: Project): FileType =
            decideFor(project, OVERRIDABLE_EXTENSION)
                ?: FileTypeManager.getInstance().getFileTypeByExtension(OVERRIDABLE_EXTENSION)

        private fun decideFor(project: Project, extension: String?): FileType? = decide(
            extension = extension,
            isBasedPythonProject = BasedPythonProjectDetector.isBasedPythonProject(project),
            handling = BasedPythonSettings.getInstance(project).pyFileHandling,
            pythonLanguageAvailable = PyFileHandling.isPythonLanguageAvailable(),
        )

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
