package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.manager.index.PackageRelease
import dev.basedpython.pycharm.env.manager.index.Pep440
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The rows of the version picker: which releases are offered, and what has to be said about each.
 *
 * Two things make a release something other than a plain choice, and both are worth saying out loud
 * rather than acting on silently:
 *
 * - **Yanked** (PEP 592). The maintainer withdrew it, usually because it was broken. A resolver will
 *   not pick one on its own, but a user who pins it explicitly still gets it — which is exactly why
 *   the row has to say so instead of the list quietly omitting it.
 * - **Incompatible.** The release declares a `requires_python` this environment does not satisfy, so
 *   installing it cannot work. Also shown rather than hidden, with the requirement stated, because
 *   "3.13 is not offered" is a puzzle and "3.13 needs Python >=3.14" is an answer.
 *
 * Nothing is filtered out. A version missing from a list with no explanation is the worst of the
 * three outcomes.
 */
internal object EnvVersionChoices {

    /** One row of the picker. */
    data class Choice(
        /** The version to pin, or null for the row that pins nothing. */
        val version: String?,
        val label: String,
        val yanked: Boolean,
        /** True when this release cannot run on the environment's interpreter. */
        val incompatible: Boolean,
    ) {
        /** True when picking this is a choice the user should look at twice. */
        val isQuestionable: Boolean get() = yanked || incompatible
    }

    /**
     * The rows for [releases], newest first, under the row that pins nothing.
     *
     * [pythonVersion] is the environment's interpreter — the thing `requires_python` is judged
     * against. When it is unknown, nothing is marked incompatible: an environment that has not been
     * created yet cannot rule anything out, and guessing would grey out versions that are fine.
     */
    fun of(releases: List<PackageRelease>, pythonVersion: String?): List<Choice> {
        val rows = mutableListOf(
            Choice(
                version = null,
                label = BasedPythonBundle.message("env.version.any"),
                yanked = false,
                incompatible = false,
            ),
        )
        releases.sortedWith(compareBy(Pep440.NEWEST_FIRST) { it.version }).forEach { release ->
            val incompatible = pythonVersion != null &&
                !Pep440.satisfies(pythonVersion, release.requiresPython)
            rows += Choice(
                version = release.version,
                label = label(release, incompatible),
                yanked = release.yanked,
                incompatible = incompatible,
            )
        }
        return rows
    }

    /**
     * What a row reads as.
     *
     * Both notes can apply at once and both are shown: a yanked release that also needs a newer
     * Python is two separate reasons not to pick it, and dropping either would leave the user
     * fixing one and hitting the other.
     */
    private fun label(release: PackageRelease, incompatible: Boolean): String {
        val notes = buildList {
            if (release.yanked) {
                add(
                    release.yankedReason
                        ?.let { BasedPythonBundle.message("env.version.yanked.reason", it) }
                        ?: BasedPythonBundle.message("env.version.yanked"),
                )
            }
            if (incompatible) {
                add(
                    release.requiresPython
                        ?.let { BasedPythonBundle.message("env.version.needsPython", it) }
                        ?: BasedPythonBundle.message("env.version.incompatible"),
                )
            }
        }
        return if (notes.isEmpty()) release.version else "${release.version}  (${notes.joinToString("; ")})"
    }

    /** The row for [version], or the pins-nothing row when it is not among them. */
    fun select(choices: List<Choice>, version: String?): Choice =
        choices.firstOrNull { it.version == version } ?: choices.first()
}
