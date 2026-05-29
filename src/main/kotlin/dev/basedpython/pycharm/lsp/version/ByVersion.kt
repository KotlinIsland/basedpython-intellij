package dev.basedpython.pycharm.lsp.version

/**
 * Minimum supported `by` CLI version. Versions below this trigger a one-time
 * warning notification on project open. Kept as a low floor on purpose.
 */
const val MIN_BY_VERSION: String = "0.1.0"

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
