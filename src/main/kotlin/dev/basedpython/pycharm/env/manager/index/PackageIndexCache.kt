package dev.basedpython.pycharm.env.manager.index

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the index says, kept on disk so the Add dialog has something to show the moment it opens.
 *
 * ### Why cache at all
 *
 * The two things the dialog wants are both expensive and both nearly immutable. The catalogue of
 * package names is a 9.5 MB download that changes only by gaining entries; a package's summary and
 * extras change only when it releases. Fetching either on demand would make the dialog useless for
 * the first few seconds every single time, for data that was correct yesterday and will be correct
 * tomorrow.
 *
 * So both are cached under `~/.basedpython/cache/index/<index-id>/`, keyed by the index they came
 * from — a private mirror and the public PyPI never share a cache — with two very different
 * lifetimes:
 *
 * - the **catalogue**, refreshed weekly, because a name list that is a few days stale costs the user
 *   nothing more than a missing completion for a package published this week;
 * - **package details**, refreshed daily, because a version number that is a day stale is a version
 *   number the user is about to install anyway.
 *
 * ### What it will not do
 *
 * Fetch anything because a project was opened. The catalogue is downloaded on the first Add — a
 * user gesture — and never before, which keeps this consistent with everything else in this feature.
 * The dialog is fully usable while it downloads; completion simply lights up when it is ready.
 */
@Service(Service.Level.APP)
internal class PackageIndexCache : Disposable {

    override fun dispose() = Unit

    /** In-flight or completed name-catalogue refreshes, so two dialogs do not both download 9.5 MB. */
    private val refreshing = ConcurrentHashMap<String, AtomicBoolean>()

    /** Package details this session has already looked up, on top of the disk cache. */
    private val details = ConcurrentHashMap<String, PackageDetails>()

    /** The catalogue for [index], whether or not it has been downloaded yet. */
    fun names(index: PackageIndex): PackageNameStore = PackageNameStore(namesFile(index))

    /** True when the catalogue is present and recent enough to leave alone. */
    fun isCatalogueFresh(index: PackageIndex): Boolean {
        val age = names(index).lastModified()?.let { System.currentTimeMillis() - it } ?: return false
        return age < CATALOGUE_TTL_MILLIS
    }

    /**
     * Downloads the catalogue unless it is already fresh, or already being downloaded.
     *
     * Blocking; call from a background thread. [onFinished] runs on the calling thread once the
     * catalogue is on disk, so a dialog can light its completion up.
     *
     * Returns false when nothing was started — the answer callers use to decide whether to say
     * "downloading package list…" at all.
     */
    fun refreshCatalogue(index: PackageIndex, force: Boolean = false, onFinished: () -> Unit = {}): Boolean {
        if (!force && isCatalogueFresh(index)) return false
        val guard = refreshing.computeIfAbsent(index.id) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return false
        try {
            val target = namesFile(index)
            PackageNameStore.Writer(target).use { writer ->
                index.fetchNames(writer::add)
                LOG.info("package catalogue for ${index.displayName}: ${writer.count} names")
            }
            onFinished()
            return true
        } catch (e: Exception) {
            // No catalogue means no completion, which the dialog already handles — the field takes
            // free text regardless. Not worth interrupting the user over.
            LOG.warn("could not refresh the package catalogue for ${index.displayName}", e)
            return false
        } finally {
            guard.set(false)
        }
    }

    /**
     * What the index knows about [name] — from memory, then disk, then the network.
     *
     * Blocking; call from a background thread. Returns null only when the index has never heard of
     * the package *and* nothing was cached, which is how the dialog tells "unknown package" from
     * "not looked up yet".
     */
    fun detailsFor(index: PackageIndex, name: String): PackageDetails? {
        val key = key(index, name)
        details[key]?.let { return it }

        readDetails(index, name)?.let {
            details[key] = it
            return it
        }

        val document = index.fetchDetailsDocument(name) ?: return null
        val fetched = index.parseDetails(name, document) ?: return null
        details[key] = fetched
        writeDocument(index, name, document)
        return fetched
    }

    /** Details already known without going to the network, for a synchronous first paint. */
    fun cachedDetailsFor(index: PackageIndex, name: String): PackageDetails? =
        details[key(index, name)] ?: readDetails(index, name)?.also { details[key(index, name)] = it }

    /** Forgets everything cached for [index]. */
    fun clear(index: PackageIndex) {
        details.keys.removeIf { it.startsWith(index.id + "/") }
        runCatching {
            val dir = directory(index)
            if (Files.isDirectory(dir)) {
                Files.walk(dir).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
                }
            }
        }
    }

    // ---- disk ---------------------------------------------------------------

    private fun key(index: PackageIndex, name: String) =
        index.id + "/" + PackageNameStore.normalise(name)

    private fun directory(index: PackageIndex): Path = root().resolve(index.id)

    private fun namesFile(index: PackageIndex): Path = directory(index).resolve("catalogue.txt")

    private fun detailsFile(index: PackageIndex, name: String): Path =
        directory(index).resolve("details").resolve(PackageNameStore.normalise(name) + ".json")

    /**
     * Reads cached details, treating anything older than a day as absent.
     *
     * Age is the file's own timestamp rather than a field inside it, so a cache entry cannot claim
     * to be fresher than it is and there is no format to migrate when the TTL changes.
     */
    private fun readDetails(index: PackageIndex, name: String): PackageDetails? {
        val file = detailsFile(index, name)
        return try {
            if (!Files.isRegularFile(file)) return null
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis()
            if (age > DETAILS_TTL_MILLIS) return null
            index.parseDetails(name, Files.readString(file))
        } catch (e: Exception) {
            LOG.debug("could not read cached details for $name", e)
            null
        }
    }

    /** Caches the index's own document, verbatim — see [PackageIndex.fetchDetailsDocument]. */
    private fun writeDocument(index: PackageIndex, name: String, document: String) {
        try {
            val file = detailsFile(index, name)
            Files.createDirectories(file.parent)
            Files.writeString(file, document)
        } catch (e: Exception) {
            LOG.debug("could not cache details for $name", e)
        }
    }

    companion object {

        private val LOG = Logger.getInstance(PackageIndexCache::class.java)

        /** A catalogue a few days stale costs at most a missing completion for a brand-new package. */
        private const val CATALOGUE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** A version number a day stale is the version the user is about to install anyway. */
        private const val DETAILS_TTL_MILLIS = 24L * 60 * 60 * 1000

        fun getInstance(): PackageIndexCache = service()

        /** `~/.basedpython/cache/index`, a sibling of the directory downloaded binaries live in. */
        fun root(): Path {
            val home = System.getProperty("user.home") ?: ""
            return Path.of(home, ByBinaryDownloadPlan.INSTALL_DIR_NAME, "cache", "index")
        }
    }
}
