package dev.basedpython.pycharm.env.manager

/**
 * When the environment is scanned, given that scans and operations both take seconds and overlap.
 *
 * Three rules, and each one is a bug it replaced:
 *
 * - **A request is never dropped.** One made while a scan is running is remembered and served when
 *   that scan ends. Answering "a scan is already running" and forgetting is how a *Sync* that
 *   finished while the manifest watcher's scan was still reading the old lock file left the view
 *   saying "out of sync" until something else happened to refresh it.
 * - **Nothing is scanned while an operation runs.** A scan in the middle of a `uv sync` reads an
 *   environment that is half installed and races uv for the same lock; the operation's end is the
 *   moment the answer is worth having, and every operation ends with exactly one.
 * - **A change already seen is not scanned for twice.** An operation's own file refresh reaches the
 *   manifest watcher, whose debounced request lands a second and a half later — after the scan that
 *   read those very files. [noteChange] and [requestIfStale] recognise that and skip it.
 *
 * Plain synchronised state, with the scan itself handed in as [startScan], so the rules can be
 * driven step by step in a test rather than raced against a coroutine.
 */
internal class EnvRefreshQueue(
    /** Starts a scan that will call [scanFinished] when it is over, however it ends. */
    private val startScan: () -> Unit,
) {
    private val lock = Any()

    private var operations = 0
    private var scanning = false
    private var pending = false

    /** Ticks on every noted change and every scan start, so the two can be put in order. */
    private var clock = 0L
    private var lastScanStartedAt = -1L

    /** True while a scan or an operation is in flight. */
    val busy: Boolean get() = synchronized(lock) { operations > 0 || scanning }

    /** Asks for a scan: now if nothing is running, otherwise as soon as it has finished. */
    fun request() {
        if (synchronized(lock) { pending = true; claimScan() }) startScan()
    }

    /**
     * Records that something a scan reads has just changed, and returns when.
     *
     * Called at the moment of the change rather than when a debounced scan for it fires, which is
     * the whole point: [requestIfStale] can then tell a scan that started after the change — and so
     * read it — from one that started before.
     */
    fun noteChange(): Long = synchronized(lock) { ++clock }

    /** [request], unless a scan has started since the change noted at [changedAt]. */
    fun requestIfStale(changedAt: Long) {
        val start = synchronized(lock) {
            if (lastScanStartedAt > changedAt) return
            pending = true
            claimScan()
        }
        if (start) startScan()
    }

    /** A scan ended; starts the next one if something asked for it meanwhile. */
    fun scanFinished() {
        val start = synchronized(lock) {
            scanning = false
            claimScan()
        }
        if (start) startScan()
    }

    /**
     * Starts an operation unless one is already running; true when this call started it.
     *
     * One at a time, because two uv commands against the same environment race for its lock and
     * its site-packages, and whichever loses fails with a message about a file lock rather than
     * about what the user did. Asked when the gesture is made, not when its background task gets
     * round to running, so two clicks in quick succession cannot both pass.
     */
    fun tryStartOperation(): Boolean = synchronized(lock) {
        if (operations > 0) return false
        operations++
        true
    }

    /** An operation ended — which always warrants a scan. */
    fun operationFinished() {
        val start = synchronized(lock) {
            check(operations > 0) { "an operation finished that never started" }
            operations--
            pending = true
            claimScan()
        }
        if (start) startScan()
    }

    /** Takes the pending request, if one can be served now. Holds [lock]. */
    private fun claimScan(): Boolean {
        if (!pending || scanning || operations > 0) return false
        pending = false
        scanning = true
        lastScanStartedAt = ++clock
        return true
    }
}
