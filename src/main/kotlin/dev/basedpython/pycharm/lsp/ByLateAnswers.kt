package dev.basedpython.pycharm.lsp

import com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One request at a time per [K] and revision, which its askers wait on together for [patience], and
 * whose answer is announced when it comes after one of them has stopped waiting.
 *
 * A highlighting pass asks about a file once per line of it. Where each ask was a request of its own
 * that waited its own two seconds, a `by` that answered nothing for a while held the pass up for two
 * seconds a line, and every line asked about before it recovered was drawn as though `by` had said
 * there was nothing there: the answer was dropped, and nothing ran the pass again. (Measured with a
 * `by` whose replies were held for 11 seconds as it started: 22 requests, and the icon of the test on
 * the first line missing until the next edit.)
 *
 * So [patience] counts from when the request was sent, not from when a caller asked: a pass waits it
 * once, however many lines it has left. The request goes on behind the callers, in [scope], and when
 * it has answered a caller that had given up on it, [late] is told, for what was drawn without the
 * answer to be drawn again.
 *
 * A caller that is cancelled leaves the request to the next one, which a pass cancelled by an edit
 * elsewhere is followed by. One about an older revision is cancelled by the first ask about a newer.
 */
internal class ByLateAnswers<K : Any>(
    private val scope: CoroutineScope,
    private val patience: Duration,
    private val late: (K) -> Unit,
) {
    private class Asked(val stamp: Long, val sent: TimeMark) {
        lateinit var answer: Deferred<Any?>

        /** Whether a caller has gone on without [answer]. */
        @Volatile
        var missed = false
    }

    private val asks = ConcurrentHashMap<K, Asked>()

    /**
     * What [ask] answers about [key] at [stamp], or null when it has not within [patience].
     *
     * Blocks, polling for the caller's cancellation. With [wait] false it only starts the request, and
     * answers what is already there: a caller that was never going to wait has missed nothing.
     */
    fun <T : Any> answer(key: K, stamp: Long, wait: Boolean, ask: suspend () -> T?): T? {
        val asked = asked(key, stamp, ask)
        if (wait) {
            awaitWithCancellation(asked)
            // set before the answer is looked at: one that comes between the two is announced as well
            if (!asked.answer.isCompleted) asked.missed = true
        }
        return asked.answered()
    }

    /** Forgets every request, cancelling those that are out. */
    fun clear() {
        val out = asks.values.toList()
        asks.clear()
        out.forEach { it.answer.cancel() }
    }

    private fun asked(key: K, stamp: Long, ask: suspend () -> Any?): Asked {
        var replaced: Asked? = null
        var started: Asked? = null
        val asked = checkNotNull(
            asks.compute(key) { _, current ->
                if (current != null && current.stamp == stamp) return@compute current
                replaced = current
                Asked(stamp, TimeSource.Monotonic.markNow()).also { new ->
                    new.answer = scope.async { ask() }
                    started = new
                }
            },
        )
        replaced?.answer?.cancel()
        // what it answered is the asker's to keep, not this one's
        started?.let { new ->
            new.answer.invokeOnCompletion {
                if (asks.remove(key, new) && new.missed && new.answered<Any>() != null) late(key)
            }
        }
        return asked
    }

    private fun awaitWithCancellation(asked: Asked) {
        val future = asked.answer.asCompletableFuture()
        while (true) {
            ProgressManager.checkCanceled()
            val left = patience - asked.sent.elapsedNow()
            if (!left.isPositive()) return
            try {
                future.get(left.inWholeMilliseconds.coerceIn(1, POLL_MS), TimeUnit.MILLISECONDS)
                return
            } catch (_: TimeoutException) {
                continue
            } catch (_: ExecutionException) {
                return
            } catch (_: CancellationException) {
                return
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T : Any> Asked.answered(): T? {
        if (!answer.isCompleted || answer.getCompletionExceptionOrNull() != null) return null
        @Suppress("UNCHECKED_CAST") // a key is asked one kind of thing
        return answer.getCompleted() as T?
    }

    private companion object {
        const val POLL_MS = 10L
    }
}
