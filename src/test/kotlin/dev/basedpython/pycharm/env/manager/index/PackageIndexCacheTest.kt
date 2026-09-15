package dev.basedpython.pycharm.env.manager.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The catalogue refresh, and the race that made the first Add on a machine look broken.
 *
 * Opening the dialog starts a 9.5 MB download. Typing immediately afterwards asked a catalogue that
 * did not exist yet and got nothing, which the lookup rendered as "No suggestions" — while the very
 * same query a few seconds later, on Ctrl+Space, returned the right names. The answer was never
 * wrong, it was early, and the fix is that a caller who needs the catalogue can wait for the
 * download already in flight instead of being told there is none.
 */
class PackageIndexCacheTest {

    /** Every cache in this class lives here, never under the real `~/.basedpython/cache`. */
    @TempDir
    lateinit var root: Path

    /** An index whose catalogue takes a controllable amount of time to arrive. */
    private class SlowIndex(
        override val id: String,
        private val started: CountDownLatch = CountDownLatch(0),
        private val release: CountDownLatch = CountDownLatch(0),
    ) : PackageIndex {
        override val displayName: String = id
        val fetches = AtomicInteger()

        override fun fetchNames(consumer: (String) -> Unit) {
            fetches.incrementAndGet()
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            listOf("httpx", "requests", "based-cli").forEach(consumer)
        }

        override fun fetchDetailsDocument(name: String): String? = null
        override fun parseDetails(name: String, document: String): PackageDetails? = null
    }

    /**
     * The whole point: two callers, one download.
     *
     * Without this the second caller was told "a refresh is already running" and returned an empty
     * list, which is what the user saw.
     */
    @Test
    fun `a second caller waits for the download already in flight rather than getting nothing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val index = SlowIndex("shared-${System.nanoTime()}", started, release)
        val cache = PackageIndexCache(root)

        val first = cache.refreshCatalogue(index)
        assertTrue(started.await(10, TimeUnit.SECONDS), "the download should have started")
        assertTrue(cache.isRefreshing(index), "and should be reported as in flight")

        // A completion asking while it runs joins the same work instead of starting its own.
        val second = cache.refreshCatalogue(index)
        assertSame(first, second, "both callers share one download")

        release.countDown()
        assertEquals(true, first.get(10, TimeUnit.SECONDS))
        assertEquals(1, index.fetches.get(), "9.5 MB is fetched once, however many callers ask")
        assertTrue(cache.names(index).contains("httpx"))
    }

    /** Once it has landed, nothing is fetched again. */
    @Test
    fun `a fresh catalogue is not downloaded twice`() {
        val index = SlowIndex("fresh-${System.nanoTime()}")
        val cache = PackageIndexCache(root)

        cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS)
        assertTrue(cache.isCatalogueFresh(index))

        assertEquals(false, cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS))
        assertEquals(1, index.fetches.get())
    }

    /**
     * No catalogue is a missing convenience, not an error: the field takes free text regardless, so
     * a download that fails must complete rather than propagate.
     */
    @Test
    fun `a download that fails completes instead of throwing`() {
        val index = object : PackageIndex {
            override val id: String = "broken-${System.nanoTime()}"
            override val displayName: String = "broken"
            override fun fetchNames(consumer: (String) -> Unit) = error("no network")
            override fun fetchDetailsDocument(name: String): String? = null
            override fun parseDetails(name: String, document: String): PackageDetails? = null
        }
        val cache = PackageIndexCache(root)

        assertEquals(false, cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS))
        assertTrue(cache.names(index).startingWith("http").isEmpty())
    }

    /** A failed attempt must not poison the next one. */
    @Test
    fun `a refresh can be retried after one fails`() {
        val index = SlowIndex("retry-${System.nanoTime()}")
        val cache = PackageIndexCache(root)

        cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS)
        // Forcing goes again even though the catalogue is fresh.
        assertEquals(true, cache.refreshCatalogue(index, force = true).get(10, TimeUnit.SECONDS))
        assertEquals(2, index.fetches.get())
    }

    /**
     * A download that dies halfway leaves the previous catalogue — and its age — as they were.
     *
     * The writer used to commit on close, so the names read before the connection dropped replaced
     * the catalogue under a fresh timestamp, and the week-long TTL then kept the fragment in place.
     */
    @Test
    fun `a download that fails midway keeps the previous catalogue and does not look fresh`() {
        val id = "midway"
        val complete = SlowIndex(id)
        val cache = PackageIndexCache(root)
        assertEquals(true, cache.refreshCatalogue(complete).get(10, TimeUnit.SECONDS))
        val catalogue = root.resolve(id).resolve("catalogue.txt")
        val stale = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        Files.setLastModifiedTime(catalogue, stale)

        val dropped = object : PackageIndex {
            override val id: String = id
            override val displayName: String = "dropped"
            override fun fetchNames(consumer: (String) -> Unit) {
                consumer("aaa-partial")
                error("connection reset")
            }
            override fun fetchDetailsDocument(name: String): String? = null
            override fun parseDetails(name: String, document: String): PackageDetails? = null
        }

        assertEquals(false, cache.refreshCatalogue(dropped).get(10, TimeUnit.SECONDS))
        assertTrue(cache.names(dropped).contains("httpx"), "the old catalogue survives")
        assertTrue(cache.names(dropped).startingWith("aaa").isEmpty(), "and the fragment was not written")
        assertEquals(stale, Files.getLastModifiedTime(catalogue))
        assertFalse(cache.isCatalogueFresh(dropped), "so the next Add fetches it again")
    }

    /** Disposal abandons a download in flight rather than leaving plugin code running after unload. */
    @Test
    fun `disposing abandons a download in flight without writing it`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = java.util.concurrent.CompletableFuture<Throwable?>()
        val index = object : PackageIndex {
            override val id: String = "disposed"
            override val displayName: String = id
            override fun fetchNames(consumer: (String) -> Unit) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                try {
                    listOf("httpx", "requests").forEach(consumer)
                    stopped.complete(null)
                } catch (e: Throwable) {
                    stopped.complete(e)
                    throw e
                }
            }
            override fun fetchDetailsDocument(name: String): String? = null
            override fun parseDetails(name: String, document: String): PackageDetails? = null
        }
        val cache = PackageIndexCache(root)

        val refresh = cache.refreshCatalogue(index)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        cache.dispose()
        release.countDown()

        assertTrue(refresh.isCancelled, "the caller's future is cancelled")
        val thrown = stopped.get(10, TimeUnit.SECONDS)
        assertTrue(thrown is java.util.concurrent.CancellationException, "the next name stops the download: $thrown")
        assertFalse(Files.exists(root.resolve("disposed").resolve("catalogue.txt")), "nothing was committed")
    }
}
