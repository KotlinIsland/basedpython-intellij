package dev.basedpython.pycharm.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Inlay hints stayed away for good when `by` answered nothing for the three seconds the pass waits.
 * When the editor is drawn again after that, and when it must not be, is [RecoveryWatch]'s.
 */
class RecoveryWatchTest {

    private var recovered = 0

    @Test
    fun `a server that answers again is drawn again, once for all that timed out`() {
        runBlocking {
            val watch = RecoveryWatch<String>(this) { recovered++ }
            val answered = CompletableDeferred<Boolean>()
            repeat(5) { watch.timedOut("by", 1_000) { answered.await() } }
            yield()
            assertEquals(0, recovered)
            answered.complete(true)
        }
        assertEquals(1, recovered)
    }

    @Test
    fun `a request that is always slower than its limit is not drawn again`() {
        runBlocking {
            val watch = RecoveryWatch<String>(this) { recovered++ }
            var sent = 0
            watch.timedOut("by", 50) { if (++sent == 1) true else awaitCancellation() }
        }
        assertEquals(0, recovered)
    }

    @Test
    fun `a server that stops instead is not`() {
        runBlocking { RecoveryWatch<String>(this) { recovered++ }.timedOut("by", 1_000) { false } }
        assertEquals(0, recovered)
    }

    @Test
    fun `a later timeout is watched again`() {
        repeat(2) {
            runBlocking { RecoveryWatch<String>(this) { recovered++ }.timedOut("by", 1_000) { true } }
        }
        runBlocking {
            val watch = RecoveryWatch<String>(this) { recovered++ }
            watch.timedOut("by", 1_000) { true }
            repeat(10) { yield() }
            watch.timedOut("by", 1_000) { true }
        }
        assertEquals(4, recovered)
    }
}
