package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When scans run, driven one step at a time.
 *
 * [started] counts the scans the queue asked for; a test ends one by calling
 * [EnvRefreshQueue.scanFinished], which is what the real scan does from its `finally`.
 */
class EnvRefreshQueueTest {

    private var started = 0
    private val queue = EnvRefreshQueue { started++ }

    @Test
    fun `a request with nothing running scans at once`() {
        queue.request()
        assertEquals(1, started)
        assertTrue(queue.busy)

        queue.scanFinished()
        assertFalse(queue.busy)
        assertEquals(1, started, "and nothing else was asked for")
    }

    /**
     * The bug: a *Sync* that finished while the watcher's scan was still reading the old lock asked
     * for a refresh, was told one was already running, and the view stayed stale.
     */
    @Test
    fun `a request made during a scan is served when the scan ends`() {
        queue.request()
        queue.request()
        queue.request()
        assertEquals(1, started, "never two at once")

        queue.scanFinished()
        assertEquals(2, started, "the requests made meanwhile are one more scan, not dropped")

        queue.scanFinished()
        assertEquals(2, started, "and not three")
        assertFalse(queue.busy)
    }

    @Test
    fun `nothing scans while an operation runs, and the operation ends with one scan`() {
        queue.operationStarted()
        queue.request()
        assertEquals(0, started, "the environment is half-installed; reading it now is wrong")
        assertTrue(queue.busy)

        queue.operationFinished()
        assertEquals(1, started, "the request and the operation's own are the same scan")
        queue.scanFinished()
        assertEquals(1, started)
        assertFalse(queue.busy)
    }

    @Test
    fun `an operation that ends while a scan is running is scanned for afterwards`() {
        queue.request()
        queue.operationStarted()
        queue.operationFinished()
        assertEquals(1, started, "the scan that started before the operation is still running")

        queue.scanFinished()
        assertEquals(2, started, "so what the operation changed is read by a scan of its own")
    }

    @Test
    fun `overlapping operations scan once, after the last`() {
        queue.operationStarted()
        queue.operationStarted()
        queue.operationFinished()
        assertEquals(0, started)
        queue.operationFinished()
        assertEquals(1, started)
    }

    /**
     * The redundant second scan: an operation refreshes the manifests it wrote, the watcher sees
     * that and asks for a debounced scan, and by the time the delay is up the operation's own scan
     * has already read those files.
     */
    @Test
    fun `a change a later scan has already read is not scanned for again`() {
        queue.operationStarted()
        val changedAt = queue.noteChange()
        queue.operationFinished()
        assertEquals(1, started)
        queue.scanFinished()

        queue.requestIfStale(changedAt)
        assertEquals(1, started, "the debounced request is already served")
    }

    @Test
    fun `a change made after the last scan started is scanned for`() {
        queue.request()
        val changedAt = queue.noteChange()
        queue.requestIfStale(changedAt)
        assertEquals(1, started, "not alongside the scan in flight")

        queue.scanFinished()
        assertEquals(2, started, "but after it, since it may have read the file before the change")
    }
}
