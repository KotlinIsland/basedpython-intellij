package dev.basedpython.pycharm.env.manager

/**
 * What a package is doing right now, as read from the tool's own output.
 *
 * ### Where this comes from
 *
 * uv reports per-package progress on stdout even when it is piped rather than attached to a
 * terminal — verified against 0.12.3 on a cold cache:
 *
 * ```
 * Downloading scipy (19.5MiB)
 * Downloading numpy (5.1MiB)
 *  Downloaded numpy
 *  + numpy==2.5.2
 * ```
 *
 * Several downloads run at once and each is announced as it starts and again as it finishes, which
 * is exactly enough to put a spinner on the right rows. What it does *not* give is progress for a
 * package already in the cache: that one is never downloaded, only listed in the final `+` block, so
 * it goes from nothing to installed with no state in between. That is honest — it really is
 * instantaneous — and is why a sync of an already-warm cache shows almost no spinners.
 */
enum class EnvPackageActivity {
    /** Being fetched from the index. */
    DOWNLOADING,

    /** Fetched, not yet installed into the environment. */
    PREPARING,

    /** Being removed. */
    REMOVING,
}

/** One thing the tool said about one package. */
internal sealed interface EnvProgressEvent {
    data class Downloading(val name: String, val size: String?) : EnvProgressEvent
    data class Downloaded(val name: String) : EnvProgressEvent
    data class Installed(val name: String, val version: String) : EnvProgressEvent
    data class Uninstalled(val name: String, val version: String) : EnvProgressEvent
}

/**
 * Reads a line of the tool's output into an event, or nothing.
 *
 * A parser over another program's human-readable output, so it is deliberately narrow: it matches
 * the four shapes that carry per-package state and ignores everything else, including the
 * `Resolved`/`Prepared`/`Installed N packages` summaries, which say how many rather than which.
 *
 * Unrecognised output is not an error. The worst case is a spinner that does not appear, which is
 * exactly where this feature started.
 */
internal object EnvProgressLine {

    private val DOWNLOADING = Regex("""^\s*Downloading\s+(\S+?)\s*(?:\(([^)]*)\))?\s*$""")
    private val DOWNLOADED = Regex("""^\s*Downloaded\s+(\S+)\s*$""")

    /** ` + numpy==2.5.2` and ` - urllib3==2.7.0`, the block printed once the work is done. */
    private val CHANGED = Regex("""^\s*([+-])\s+(\S+?)==(\S+)\s*$""")

    fun parse(line: String): EnvProgressEvent? {
        DOWNLOADING.matchEntire(line)?.let { m ->
            // "Downloading 5 packages" is a summary, not a package named `5`.
            val name = m.groupValues[1]
            if (name.toIntOrNull() != null) return null
            return EnvProgressEvent.Downloading(name, m.groupValues[2].takeIf { it.isNotEmpty() })
        }
        DOWNLOADED.matchEntire(line)?.let { m ->
            val name = m.groupValues[1]
            if (name.toIntOrNull() != null) return null
            return EnvProgressEvent.Downloaded(name)
        }
        CHANGED.matchEntire(line)?.let { m ->
            val name = m.groupValues[2]
            val version = m.groupValues[3]
            return if (m.groupValues[1] == "+") {
                EnvProgressEvent.Installed(name, version)
            } else {
                EnvProgressEvent.Uninstalled(name, version)
            }
        }
        return null
    }
}

/**
 * Which packages are busy, built up from the tool's output as it runs.
 *
 * Keyed on the normalised package name so `Flask-SQLAlchemy` in the tree matches `flask_sqlalchemy`
 * in the output. Immutable snapshots rather than a mutable map the view reads directly: the view
 * paints on the EDT while the output arrives on a process thread, and a snapshot is the cheap way to
 * keep those from tearing.
 */
internal data class EnvProgress(
    private val activity: Map<String, EnvPackageActivity> = emptyMap(),
    /** The most recent thing worth putting in the header, e.g. `Downloading scipy`. */
    val headline: String? = null,
) {

    val isEmpty: Boolean get() = activity.isEmpty()

    /** What [name] is doing, or null when it is not busy. */
    fun activityOf(name: String): EnvPackageActivity? = activity[key(name)]

    /** This progress with [event] applied. */
    fun with(event: EnvProgressEvent): EnvProgress {
        val next = activity.toMutableMap()
        val headline: String? = when (event) {
            is EnvProgressEvent.Downloading -> {
                next[key(event.name)] = EnvPackageActivity.DOWNLOADING
                event.size?.let { "${event.name} (${it})" } ?: event.name
            }

            is EnvProgressEvent.Downloaded -> {
                next[key(event.name)] = EnvPackageActivity.PREPARING
                event.name
            }

            // The `+`/`-` block is printed after the work, so an entry here is finished, not starting.
            is EnvProgressEvent.Installed -> {
                next.remove(key(event.name))
                event.name
            }

            is EnvProgressEvent.Uninstalled -> {
                next.remove(key(event.name))
                event.name
            }
        }
        return EnvProgress(next, headline)
    }

    /** Everything cleared, for when an operation ends. */
    fun cleared(): EnvProgress = EnvProgress()

    /**
     * Marks [names] as busy before the tool has said anything about them.
     *
     * What makes *Remove* show anything at all: uv prints nothing per package while uninstalling,
     * only the `-` block at the end, so without this the rows being removed would sit still until
     * they vanished.
     */
    fun starting(names: Collection<String>, what: EnvPackageActivity): EnvProgress {
        if (names.isEmpty()) return this
        val next = activity.toMutableMap()
        names.forEach { next[key(it)] = what }
        return EnvProgress(next, headline)
    }

    private companion object {
        /** PEP 503 normalisation, so the tree's spelling and the tool's agree. */
        fun key(name: String): String =
            dev.basedpython.pycharm.env.manager.index.PackageNameStore.normalise(name)
    }
}
