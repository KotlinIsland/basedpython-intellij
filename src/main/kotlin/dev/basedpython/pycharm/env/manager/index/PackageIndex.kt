package dev.basedpython.pycharm.env.manager.index

/**
 * Where a backend's packages can be looked up.
 *
 * The second seam in this feature, and separate from the backend for a reason: which *manager* runs
 * a project and which *index* it installs from are independent. Two uv projects can point at
 * different indexes — a private mirror, a company registry — and a conda-flavoured backend would
 * look packages up somewhere else entirely while still being driven the same way.
 *
 * Implementations do network IO and are called from background threads only.
 */
interface PackageIndex {

    /**
     * Stable identity for this index, used as the cache directory name.
     *
     * Must differ whenever the *contents* would differ — it is what stops a private mirror's
     * catalogue being served to a project pointed at the public one.
     */
    val id: String

    /** How the index is named in the UI. */
    val displayName: String

    /**
     * Every package name the index publishes, handed to [consumer] as it arrives.
     *
     * A callback rather than a returned collection because the answer is enormous — PyPI's is
     * 872,009 names in a 42 MB document — and it must never exist as one object. A `Sequence` would
     * read better and cannot be produced here: the names arrive inside a connection callback that
     * has to stay open for the duration and closed at the end of it, which is exactly what a lazy
     * sequence escaping that scope would break.
     */
    fun fetchNames(consumer: (String) -> Unit)

    /**
     * The index's own metadata document for [name], or null when it has no such package.
     *
     * The raw document rather than a parsed object, because that is what gets cached. Storing what
     * the index said and re-parsing it with [parseDetails] means the cache never needs a format
     * version of its own: a parser that learns a new field picks it up from entries already on disk,
     * instead of finding them written in an older shape.
     */
    fun fetchDetailsDocument(name: String): String?

    /** Reads a document previously produced by [fetchDetailsDocument]. */
    fun parseDetails(name: String, document: String): PackageDetails?

    /** Fetch and parse in one step, for callers with no cache to consult. */
    fun fetchDetails(name: String): PackageDetails? =
        fetchDetailsDocument(name)?.let { parseDetails(name, it) }
}

/**
 * What an index says about a single package.
 *
 * Deliberately small: the fields that change what a person types into the Add dialog, and nothing
 * else. [extras] is the one that could not be answered locally at all before — a package's optional
 * feature sets are declared in its metadata, so without asking the index there is no way to know
 * that `httpx` offers `http2` short of reading its documentation.
 */
data class PackageDetails(
    val name: String,
    /** The newest version the index offers, or null when that could not be read. */
    val latestVersion: String?,
    /** One-line description. */
    val summary: String?,
    /** The optional feature sets this package declares, e.g. `http2`, `cli`. */
    val extras: List<String>,
    /** Project or documentation URL, for the link in the dialog. */
    val homepage: String?,
    /**
     * Every release the index offers, newest first.
     *
     * Carries what the version picker has to say about each one beyond its number: a yanked release
     * is one the maintainer withdrew and should not be installed fresh, and one whose
     * `requires_python` excludes this environment cannot be installed at all. Both are shown rather
     * than hidden — a version missing from the list with no explanation is worse than one that says
     * why it is unavailable.
     */
    val releases: List<PackageRelease> = emptyList(),
) {
    companion object {
        /** What is known about a package the index has never heard of. */
        fun unknown(name: String): PackageDetails =
            PackageDetails(name, null, null, emptyList(), null, emptyList())
    }
}

/**
 * One release of a package, as the version picker needs it.
 *
 * [requiresPython] is the raw specifier the release declares, kept unevaluated because whether it is
 * satisfied depends on the environment being looked at — the same release is installable in one
 * project and not in another, so the judgement belongs at the point of display, not here.
 */
data class PackageRelease(
    val version: String,
    /** The maintainer withdrew this release; PEP 592. */
    val yanked: Boolean,
    /** Why it was withdrawn, when they said. */
    val yankedReason: String?,
    /** e.g. `>=3.8`, or null when the release declares nothing. */
    val requiresPython: String?,
)
