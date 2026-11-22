package dev.basedpython.pycharm.run.test

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.run.test.tree.ByTestSources

/**
 * How a test configuration names what pytest runs: each target the project's own file, `.by` or
 * `.py`, by its path from the project, as pytest names a file from its rootdir, with a node id's names
 * after it — `tests/test_calc.by::test_add`. That is what `by run --in-place pytest` is handed and what
 * it reports, so a configuration, a test tree's node id and a rerun of failed tests all speak one
 * language, and a configuration says the same thing whichever way `by` runs it. A configuration made
 * here runs pytest in the project's directory.
 */
internal object ByTestTargets {

    /** The node id separator: what follows the file is the names leading to a test in it. */
    private const val NAMES = "::"

    private const val BY_EXTENSION = "by"

    private const val BY_SUFFIX = ".$BY_EXTENSION"

    /**
     * The target for [file]: its path from the project, as [ByTestSources.relativePath] names a file
     * in a node id; null when the project does not hold it.
     */
    fun of(project: Project, file: VirtualFile): String? = ByTestSources.relativePath(project, file)

    /**
     * Whether a run of test files with [extensions] is the project's interpreter's rather than `by
     * run`'s; null when it is neither's, as one run.
     *
     * A `by run` in place runs a `.by` and a `.py` test alike, named by the project's own files, in a
     * project of `.py` alone too. A `by run` that stages does stage a `.py`, but refuses a project with
     * no `.by`, and does not say where: `by/buildOutput` places only a `.by`, and in a src layout
     * `src/tests/test_p.py` is staged as `tests/test_p.py`, a path pytest has to be given (all measured
     * against by 0.0.3 and, in place, 99f067bc2). So there a `.py` is run where it is.
     */
    fun plain(extensions: Collection<String?>, inPlace: Boolean): Boolean? = when {
        inPlace -> false
        extensions.none { it == BY_EXTENSION } -> true
        extensions.all { it == BY_EXTENSION } -> false
        else -> null
    }

    /**
     * The `.by` files [paths] names, by their path from the project, once each in order: what a run
     * that is not in place has to ask `by` where it stages.
     */
    fun sources(paths: String): List<String> =
        ParametersListUtil.parse(paths).map { it.substringBefore(NAMES) }.filter { it.endsWith(BY_SUFFIX) }.distinct()

    /** What writing a configuration's targets for the tree a run stages came to. */
    sealed interface Staged {
        /** The targets, each `.by` named where the tree holds its python. */
        data class Paths(val paths: String) : Staged

        /** `by` has not said where [source], a `.by` target, is staged, so there is no target to name. */
        data class Unknown(val source: String) : Staged
    }

    /**
     * [paths] as pytest is handed them in the tree `by run` stages, for a run that is not in place:
     * each `.by` target named by [stagedPath] — `by`'s answer for where the tree holds the python of
     * the `.by` at a path from the project, which follows the module tree rather than the
     * directories — with its names after it. Any other target is handed on as written: a staged
     * tree holds no `.by`, and pytest is not handed one there.
     */
    fun staged(paths: String, stagedPath: (source: String) -> String?): Staged {
        val written = ParametersListUtil.parse(paths)
        val staged = written.map { target ->
            val file = target.substringBefore(NAMES)
            if (!file.endsWith(BY_SUFFIX)) return@map target
            val where = stagedPath(file) ?: return Staged.Unknown(file)
            where + target.removePrefix(file)
        }
        return Staged.Paths(ParametersListUtil.join(staged))
    }
}
