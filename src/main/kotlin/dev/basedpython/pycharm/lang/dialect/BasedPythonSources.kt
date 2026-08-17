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
     * The extensions a module name can resolve to, in the order `by run` resolves them.
     *
     * `.by` first, and that is the real precedence rather than a preference: `by run` transpiles
     * `main.by` into its temp directory and makes that directory `sys.path[0]`, so where `main.by`
     * and `main.py` both exist the generated module shadows the plain one.
     */
    val MODULE_EXTENSIONS: List<String> = listOf(BY, PY)

    /** True when [file] is basedpython source this plugin runs and debugs. */
    fun isOwnedSource(file: VirtualFile?): Boolean = when (file?.extension?.lowercase()) {
        BY -> true
        PY -> FileTypeRegistry.getInstance().isFileOfType(file, BasedPythonFileType.INSTANCE)
        else -> false
    }

    /**
     * True when a top-level `main` in [file] *is* the program's entry point and its command line.
     *
     * `.by` only, and this is a real difference rather than a conservative guess. basedpython reads
     * a top-level `main` as the program's command-line interface: it builds an argparse parser from
     * the signature and emits the `if __name__ == "__main__"` guard that feeds it, which is what the
     * argument form and the `def main(` gutter icon exist to serve. A plain `.py` gets none of that
     * — `by run` does not transpile it, so the interpreter runs exactly what is written, and a bare
     * `def main(name)` with no guard below it is a function nothing ever calls. Verified by running
     * both: the `.by` printed its greeting, the `.py` printed nothing at all.
     *
     * So a `.py` is offered the run configuration and the guard's icon, and not the argument form.
     */
    fun hasGeneratedEntryPoint(file: VirtualFile?): Boolean =
        file?.extension.equals(BY, ignoreCase = true)

    /**
     * [name] without whichever of [MODULE_EXTENSIONS] it ends in, or null when it ends in none.
     *
     * Null rather than the name unchanged: a caller building a module name out of a path needs to
     * know that the file was not a module at all.
     */
    fun withoutModuleExtension(name: String): String? =
        MODULE_EXTENSIONS.firstNotNullOfOrNull { extension ->
            name.removeSuffix(".$extension").takeIf { it != name }
        }
}
