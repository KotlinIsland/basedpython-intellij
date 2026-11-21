package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Which files this plugin treats as **executable basedpython source** — the ones it will offer a run
 * configuration for, put a run icon beside, and let a breakpoint or log point sit in.
 *
 * `.by` always. `.py` **only when this plugin owns the file type**, which is the question
 * [BasedPythonFileTypeOverrider] already answers from the project markers, the *Settings |
 * basedpython* ownership choice, and whether another plugin provides the Python language. Asking the
 * registry rather than re-deriving that from its inputs is what keeps the two answers from drifting:
 * a file this plugin does not own is one PyCharm's Python support is handling, and offering our own
 * run configuration and our own breakpoint type beside PyCharm's would be two of everything.
 *
 * A `.py` in a basedpython project is not a second-class citizen at runtime. `by run` transpiles
 * only `.by`, so a `.py` module is imported by the interpreter straight from where it was written —
 * `by run helper` runs `helper.py` (given the working directory on `PYTHONPATH`, which
 * `ByCommandLineState` arranges), and a breakpoint in it needs no source map because the file the
 * breakpoint names is the file that runs.
 *
 * `.byi` and `.pyi` stubs are excluded everywhere: they declare, they do not execute.
 */
object BasedPythonSources {

    /** `.by`, the extension that is always ours. */
    const val BY: String = "by"

    /** `.py`, ours only when [isOwnedSource] says the file type is. */
    const val PY: String = BasedPythonFileTypeOverrider.OVERRIDABLE_EXTENSION

    /**
     * The extensions of the modules `by run` stages and runs.
     *
     * Not an order of precedence: where `main.by` and `main.py` both exist, `by run` refuses to stage
     * the two onto one `main.py` rather than letting either shadow the other. Which file a module name
     * means is `by/runModules`' answer.
     */
    val MODULE_EXTENSIONS: List<String> = listOf(BY, PY)

    /** True when [file] is basedpython source this plugin runs and debugs. */
    fun isOwnedSource(file: VirtualFile?): Boolean = when (file?.extension?.lowercase()) {
        BY -> true
        PY -> FileTypeRegistry.getInstance().isFileOfType(file, BasedPythonFileType.INSTANCE)
        else -> false
    }

    /**
     * True when [file] is written in basedpython's own syntax: anything of the basedpython language
     * but a `.py` or `.pyi`, so `.by`, `.byi`, and a code fragment.
     *
     * A `.py` this plugin owns is still plain Python. Owning it re-types the file so it gets
     * highlighting and `by`'s code intelligence (see [PyFileHandling]); the text is not transpiled —
     * `by run` imports a `.py` straight from where it was written — so `data class`, `enum class`,
     * `let` and the other basedpython forms are syntax errors there, and editor help that writes
     * code has to write Python. The other way round, a `.py`'s Python is what a `.by` gets for free:
     * there a top-level `def main` is the program's entry point and basedpython generates the
     * `if __name__ == "__main__"` guard, which a `.py` has to write by hand.
     */
    fun isWrittenInBasedPython(file: VirtualFile?): Boolean = when (file?.extension?.lowercase()) {
        PY, PYI -> false
        else -> true
    }

    private const val PYI: String = "pyi"
}
