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
        /**
         * True for an alpha, beta, release candidate or dev build.
         *
         * Marked rather than hidden, and worth marking because a resolver will not choose one on its
         * own: pinning it is the only way to get it, so the row has to say that is what it is.
         */
        val preRelease: Boolean = false,
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
                preRelease = Pep440.isPreRelease(release.version),
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
            if (Pep440.isPreRelease(release.version)) {
                add(BasedPythonBundle.message("env.version.prerelease"))
            }
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

    /**
     * The newest release, when it is a pre-release the index does not call "latest".
     *
     * An index reports its newest *stable* release as the latest, so a package whose real work
     * happens in alphas — `basedpython` itself, at the time of writing — reports a long-superseded
     * stable and never mentions the rest. Saying only that is misleading; saying only the alpha
     * would be worse, since a resolver will not pick one. So both are shown, and this is the half
     * the index does not tell you.
     */
    fun newerPreRelease(releases: List<PackageRelease>, latestStable: String?): String? {
        val newest = releases.map { it.version }.maxWithOrNull(Pep440::compare) ?: return null
        if (!Pep440.isPreRelease(newest)) return null
        if (latestStable != null && Pep440.compare(newest, latestStable) <= 0) return null
        return newest
    }

    /** The row for [version], or the pins-nothing row when it is not among them. */
    fun select(choices: List<Choice>, version: String?): Choice =
        choices.firstOrNull { it.version == version } ?: choices.first()
}
