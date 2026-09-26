package dev.basedpython.pycharm.debug.recompose

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The `ui_tracing_off` refusal's sentence, from the wire document's refusal table. */
private const val TRACING_OFF = "tracing is off in the program's runtime; start it with trace=True"

/** What a pull that timed out says. */
private const val TIMED_OUT = "bpd did not answer within 2 s"

/**
 * What [ByRecompositionSession] does with what arrives, driven through a link that answers as
 * told: the link guard on every answer that carries data, the ticket that keeps a stale pull from
 * overwriting a newer one, the watch sent again until bpd confirms, a refusal of the watch that is
 * never the window's state, no answer as a state of its own, the bound on what is held, and a
 * stream drop kept as a gap.
 */
@TestFixtures
class ByRecompositionSessionTest {

    private val fixture by codeInsightFixture()

    private val service get() = ByRecompositionSession.getInstance(fixture.project)

    /** A link that records what it was asked and answers as told; [inFlight] says when it is done. */
    private class Scripted(
        @Volatile var pullAnswer: ByRecompositionAnswer = ByRecompositionAnswer.Unavailable(TIMED_OUT),
        @Volatile var watchAnswer: ByRecompositionAnswer = watching(true),
    ) : ByRecompositionLink {
        val watched = CopyOnWriteArrayList<Boolean>()
        val pulls = AtomicInteger()
        val inFlight = AtomicInteger()

        override fun pull(): ByRecompositionAnswer {
            inFlight.incrementAndGet()
            try {
                pulls.incrementAndGet()
                return pullAnswer
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun watch(on: Boolean): ByRecompositionAnswer {
            inFlight.incrementAndGet()
            try {
                watched += on
                return watchAnswer
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private var link: Scripted? = null

    private fun start(link: Scripted = Scripted()): Scripted {
        this.link = link
        service.sessionStarted(link)
        return link
    }

    @AfterEach
    fun forget() = onEdt {
        link?.let { waitUntil("the link's requests to finish") { it.inFlight.get() == 0 } }
        link?.let(service::sessionEnded)
        link = null
        service.setWatching(false)
        BasedPythonSettings.getInstance(fixture.project).loadState(BasedPythonSettings.State())
    }

    private fun run(frame: Long, scope: Long, runtime: Int = 0) = ByRecord.Run(
        runtime = runtime, frame = frame, scope = scope, parent = 0, name = "Counter",
        defined = ByTraceLocation("/app/counter.by", 1, null, null), called = null, key = null, origin = "self",
        causes = listOf(ByCause.Created), skipped = emptyList(), disposed = emptyList(), elapsedNs = 1,
    )

    private fun read(vararg records: ByRecord, dropped: Long = 0) = ByRecompositions.Reply.Read(
        ByRecompositions.Answer(runtimes = 1, tracing = true, kept = records.toList(), dropped = dropped, unreadable = 0),
    )

    private fun live() = service.state as ByRecompositionSession.State.Live

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("gave up waiting for $what")
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /**
     * Wait for every request the link has been sent to answer and be taken — the service's own
     * count, since a taken answer can send another request after the link has gone quiet.
     */
    private fun settled(link: Scripted, and: () -> Boolean = { true }) {
        waitUntil("the link to settle") { service.atRest && link.inFlight.get() == 0 && and() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    // ---- the link guard --------------------------------------------------------

    /**
     * Two bpd sessions at once. The service follows the one that started last, and the events the
     * other one's program is still pushing are that program's records, not this one's.
     */
    @Test
    fun `a watch event from a session that is not the current one is dropped`() = onEdt {
        val older = Scripted()
        service.sessionStarted(older)
        val newer = start()
        service.append(older, ByEvent(run(1, 5), droppedBefore = 3))
        assertTrue(service.records.isEmpty(), "another session's events reached this one: ${service.records}")
        service.append(newer, ByEvent(run(1, 5), 0))
        assertEquals(listOf(run(1, 5)), service.records)
    }

    @Test
    fun `a pull's answer landing after the session ended is dropped`() = onEdt {
        val link = start()
        service.sessionEnded(link)
        service.publish(link, read(run(1, 5)))
        assertTrue(service.records.isEmpty(), "a dead session's records came back")
        assertEquals(ByRecompositionSession.State.NoSession, service.state)
        service.refused(link, TRACING_OFF)
        service.unanswered(link, TIMED_OUT)
        assertEquals(ByRecompositionSession.State.NoSession, service.state, "a late refusal or timeout fabricated a live state")
        this.link = null
    }

    @Test
    fun `a pull's answer of an older session leaves the newer one alone`() = onEdt {
        val older = start()
        service.publish(older, read(run(1, 5)))
        val newer = start()
        assertTrue(service.records.isEmpty())

        service.publish(older, read(run(2, 6), dropped = 9))
        assertTrue(service.records.isEmpty(), "the older session's records were merged into the newer one")
        assertEquals(0L, live().dropped)
        service.refused(older, TRACING_OFF)
        assertNull(live().refusal)
        service.unanswered(older, TIMED_OUT)
        assertNull(live().unanswered)

        service.publish(newer, read(run(3, 7)))
        assertEquals(listOf(run(3, 7)), service.records)
    }

    @Test
    fun `a stale pull cannot overwrite a newer one's answer`() = onEdt {
        val link = start()
        service.publish(link, read(run(2, 5), dropped = 7), ticket = 1_000)
        service.publish(link, read(run(1, 4), dropped = 3), ticket = 999)
        assertEquals(listOf(run(2, 5)), service.records, "the older pull's records replaced the newer's")
        assertEquals(7L, live().dropped, "the older pull's notes replaced the newer's")
        service.refused(link, TRACING_OFF, ticket = 998)
        assertNull(live().refusal)
        service.unanswered(link, TIMED_OUT, ticket = 997)
        assertNull(live().unanswered)
    }

    // ---- the watch -------------------------------------------------------------

    /**
     * The preference is on and bpd has not confirmed: the watch goes out again at the stop and
     * after each pull, once bpd confirms it stops, and while bpd refuses the refusal is the
     * toolbar's to say — never the window's state.
     */
    @Test
    fun `the watch is sent again at a stop and after a pull until bpd confirms`() = onEdt {
        service.setWatching(true)
        val link = start(Scripted(pullAnswer = ByRecompositionAnswer.Answered(emptyAnswer()), watchAnswer = ByRecompositionAnswer.Refused(TRACING_OFF)))

        service.paused(link)
        settled(link) { live().pulled && live().watchProblem != null }
        val atTheStop = link.watched.size
        assertTrue(atTheStop in 1..2, "sent at the stop, and possibly again after the pull: ${link.watched}")
        assertTrue(link.watched.all { it }, "asked to watch, never to stop: ${link.watched}")
        assertEquals(TRACING_OFF, live().watchProblem)
        assertNull(live().watching, "a refusal is not a confirmation")
        assertNull(live().refusal, "a refused watch must not be shown as the window's refusal")
        assertTrue(live().pulled)

        service.pull()
        settled(link) { link.pulls.get() == 2 }
        assertEquals(atTheStop + 1, link.watched.size, "a pull that succeeds sends the watch again while unconfirmed")

        link.watchAnswer = watching(true)
        service.pull()
        settled(link) { live().watching == true }
        assertEquals(atTheStop + 2, link.watched.size)
        assertNull(live().watchProblem, "bpd confirmed, so there is no problem to show")

        service.pull()
        settled(link) { link.pulls.get() == 4 }
        assertEquals(atTheStop + 2, link.watched.size, "confirmed: nothing more to send")
    }

    /** With the preference off nothing is sent, however often the program stops. */
    @Test
    fun `no watch is sent while the preference is off`() = onEdt {
        val link = start(Scripted(pullAnswer = ByRecompositionAnswer.Answered(emptyAnswer())))
        service.paused(link)
        settled(link) { live().pulled }
        service.pull()
        settled(link) { link.pulls.get() == 2 }
        assertTrue(link.watched.isEmpty(), link.watched.toString())
    }

    /**
     * The watch a session's `launch` asks for, which nothing answers: the preference, and only while
     * the setting is on. Asked in the launch so that it is on before the program runs a line,
     * whatever order the platform sends its other requests in.
     */
    @Test
    fun `the launch asks for the stream exactly when the preference and the setting are on`() = onEdt {
        val settings = BasedPythonSettings.getInstance(fixture.project)
        settings.debuggerRecompositions = true
        assertFalse(service.watchesFromTheStart, "the preference is off")
        service.setWatching(true)
        assertTrue(service.watchesFromTheStart)
        settings.debuggerRecompositions = false
        assertFalse(service.watchesFromTheStart, "the setting is off, so the launch must not ask")
    }

    /**
     * A watch asked for in the launch gets no answer, so the toggle shows the preference and no
     * confirmation until the first stop sends it again and bpd confirms it.
     */
    @Test
    fun `a watch asked for in the launch is confirmed at the first stop`() = onEdt {
        service.setWatching(true)
        val link = start(Scripted(pullAnswer = ByRecompositionAnswer.Answered(emptyAnswer())))
        assertNull(live().watching, "nothing has confirmed the launch's watch yet")
        assertNull(live().watchProblem)

        service.paused(link)
        settled(link) { live().watching == true }
        assertEquals(listOf(true), link.watched)
        assertNull(live().watchProblem)
    }

    /**
     * A watch toggled on in the moment before the launch: bpd holds it and answers `pending`.
     * Neither a problem nor bpd confirming the stream is off — and the first stop confirms it.
     */
    @Test
    fun `a watch bpd holds until the launch is no problem and is confirmed at the first stop`() = onEdt {
        val link = start(
            Scripted(
                pullAnswer = ByRecompositionAnswer.Answered(emptyAnswer()),
                watchAnswer = ByRecompositionAnswer.Answered(json("""{"watching": false, "pending": true}""")),
            ),
        )
        service.setWatching(true)
        settled(link) { link.watched.size == 1 }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(live().watchProblem, "a held watch was reported as a problem")
        assertNull(live().watching, "a held watch was shown as bpd confirming the stream is off")

        link.watchAnswer = watching(true)
        service.paused(link)
        settled(link) { live().watching == true }
        assertEquals(listOf(true, true), link.watched)
        assertNull(live().watchProblem)
    }

    @Test
    fun `a toggle mid-session is sent, and its answer is what bpd last confirmed`() = onEdt {
        val link = start()
        service.setWatching(true)
        settled(link) { live().watching == true }
        assertEquals(listOf(true), link.watched)

        link.watchAnswer = watching(false)
        service.setWatching(false)
        settled(link) { live().watching == false }
        assertEquals(listOf(true, false), link.watched)
    }

    // ---- no answer ---------------------------------------------------------------

    @Test
    fun `a pull that gets no answer is a state with a sentence, cleared by the next answer`() = onEdt {
        val link = start(Scripted(pullAnswer = ByRecompositionAnswer.Unavailable(TIMED_OUT)))
        service.paused(link)
        settled(link) { live().unanswered != null }
        assertEquals(TIMED_OUT, live().unanswered)
        assertTrue(live().paused)
        assertTrue(!live().pulled)

        service.publish(link, read(run(1, 5)))
        assertNull(live().unanswered)
        assertTrue(live().pulled)

        service.refused(link, TRACING_OFF)
        assertEquals(TRACING_OFF, live().refusal)
        service.unanswered(link, TIMED_OUT)
        assertEquals(TIMED_OUT, live().unanswered)
        assertEquals(TRACING_OFF, live().refusal, "a refusal stands until an answer replaces it")
    }

    // ---- the setting -----------------------------------------------------------------

    @Test
    fun `turned off, a stop asks nothing and the stream is dropped`() = onEdt {
        BasedPythonSettings.getInstance(fixture.project).debuggerRecompositions = false
        val link = start()
        service.paused(link)
        assertEquals(0, link.pulls.get(), "a pull was sent with the setting off")
        service.append(link, ByEvent(run(1, 5), 0))
        assertTrue(service.records.isEmpty())
        assertTrue(live().paused)
    }

    // ---- what is held ------------------------------------------------------------------

    @Test
    fun `the held list is bounded, and what was let go is counted`() = onEdt {
        val link = start()
        val over = 10
        for (i in 1..ByRecompositionSession.HELD_LIMIT + over) service.append(link, ByEvent(run(i.toLong(), i.toLong()), 0))
        val held = service.records
        assertEquals(ByRecompositionSession.HELD_LIMIT, held.size)
        assertEquals((over + 1).toLong(), held.first().frame, "the oldest were let go")
        assertEquals(over.toLong(), live().letGo)

        // A pull larger than the bound: the newest are kept, and the count grows
        val pulled = Array(ByRecompositionSession.HELD_LIMIT + 5) { run(it.toLong() + 1, it.toLong() + 1) }
        service.publish(link, read(*pulled))
        assertEquals(ByRecompositionSession.HELD_LIMIT, service.records.size)
        assertEquals(pulled.last(), service.records.last())
        assertEquals((over + 5).toLong(), live().letGo)

        service.clear()
        assertTrue(service.records.isEmpty())
        assertEquals(0L, live().letGo, "forgetting what is shown forgets what was let go for it")
    }

    @Test
    fun `a stream drop is kept as a gap where it happened, and a pull fills the frames it carries`() = onEdt {
        val link = start()
        service.append(link, ByEvent(run(3, 5), droppedBefore = 4))
        service.append(link, ByEvent(record = null, droppedBefore = 2))
        assertEquals(listOf(ByRecord.Gap(0, 3, 4), run(3, 5), ByRecord.Gap(0, 3, 2)), service.records)
        assertEquals(6L, ByRecompositionTree.droppedInStream(service.records))

        service.publish(link, read(run(3, 5), run(3, 6), dropped = 1))
        assertEquals(listOf(run(3, 5), run(3, 6)), service.records, "the pull is the truth for frame 3, gaps included")
        assertEquals(0L, ByRecompositionTree.droppedInStream(service.records))
        assertEquals(1L, live().dropped)
    }

    @Test
    fun `the snapshot read is the same list until something changes`() = onEdt {
        val link = start()
        service.append(link, ByEvent(run(1, 5), 0))
        val first = service.records
        assertTrue(first === service.records, "no change, no copy")
        service.append(link, ByEvent(run(2, 5), 0))
        assertTrue(first !== service.records)
        assertEquals(1, first.size, "a snapshot handed out never changes underneath")
    }

    // ---- helpers ------------------------------------------------------------------

    private companion object {
        fun watching(on: Boolean): ByRecompositionAnswer =
            ByRecompositionAnswer.Answered(Json.parseToJsonElement("""{"watching": $on}""").jsonObject)

        fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        fun emptyAnswer(): JsonObject =
            Json.parseToJsonElement("""{ "format": 1, "runtimes": 1, "tracing": true, "records": { "kept": [], "dropped": 0 }, "mode": {} }""").jsonObject
    }
}
