package dev.basedpython.pycharm.env.manager.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val cache = PackageIndexCache()

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
        cache.clear(index)
    }

    /** Once it has landed, nothing is fetched again. */
    @Test
    fun `a fresh catalogue is not downloaded twice`() {
        val index = SlowIndex("fresh-${System.nanoTime()}")
        val cache = PackageIndexCache()

        cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS)
        assertTrue(cache.isCatalogueFresh(index))

        assertEquals(false, cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS))
        assertEquals(1, index.fetches.get())
        cache.clear(index)
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
        val cache = PackageIndexCache()

        assertEquals(false, cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS))
        assertTrue(cache.names(index).startingWith("http").isEmpty())
        cache.clear(index)
    }

    /** A failed attempt must not poison the next one. */
    @Test
    fun `a refresh can be retried after one fails`() {
        val index = SlowIndex("retry-${System.nanoTime()}")
        val cache = PackageIndexCache()

        cache.refreshCatalogue(index).get(10, TimeUnit.SECONDS)
        // Forcing goes again even though the catalogue is fresh.
        assertEquals(true, cache.refreshCatalogue(index, force = true).get(10, TimeUnit.SECONDS))
        assertEquals(2, index.fetches.get())
        cache.clear(index)
    }
}
