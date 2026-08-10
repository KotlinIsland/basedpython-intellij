package dev.basedpython.pycharm.lsp.version

/**
 * Minimum supported `by` CLI version. Versions below this trigger a one-time
 * warning notification on project open.
 *
 * **This must never exceed a version that actually exists.** basedpython is at `0.0.1a9`, so the
 * previous floor of `0.1.0` was above every release there has ever been: it stayed quiet only
 * because the binary currently answers `by --version` with `by unknown`, which parses to nothing.
 * The moment `by` stamps its real version, that floor would have told every user their up-to-date
 * install was out of date, forever.
 *
 * There is no known-bad older release to warn about, so the floor sits at the bottom of the
 * current series and the check is effectively a guard against something ancient. Raise it only for
 * a version the plugin genuinely requires, and never past the latest published one.
 */
const val MIN_BY_VERSION: String = "0.0.1"

/**
 * A parsed semantic-ish version (`major.minor.patch`). Trailing components
 * default to 0 and any pre-release / build suffix is ignored for comparison.
 */
internal data class ByVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ByVersion> {

    override fun compareTo(other: ByVersion): Int =
        compareValuesBy(this, other, ByVersion::major, ByVersion::minor, ByVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VERSION_REGEX = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /**
         * Extracts the first `X[.Y[.Z]]` token from arbitrary CLI output such as
         * `by 1.2.3`, `by version 0.4`, or `0.1.0-rc1`. Returns `null` if none found.
         */
        fun parse(raw: String?): ByVersion? {
            if (raw.isNullOrBlank()) return null
            val m = VERSION_REGEX.find(raw) ?: return null
            return ByVersion(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
                patch = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0,
            )
        }
    }
}
