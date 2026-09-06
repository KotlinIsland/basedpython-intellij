package dev.basedpython.pycharm.debug.recompose

import com.google.gson.JsonObject
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The trace records of the current bpd session, and what is known about the session itself.
 *
 * ## what is held, and for how long
 *
 * Unlike [dev.basedpython.pycharm.debug.dfa.ByDataFlowSession], the records are worth keeping
 * across stops: a recomposition history is the answer to "why did that happen", and the stop where
 * the question is asked is rarely the stop where it happened. So records are **appended** by watch
 * events, **merged** with a pull at every stop ([ByRecordMerge] — the pull is the truth for every
 * frame it carries), kept across a resume, and forgotten when the session ends. The margin labels
 * are the exception and describe one stop: they are computed from the latest frame when a pull
 * lands and cleared when the program resumes.
 *
 * What is held is bounded at [HELD_LIMIT]: the runtime's own ring holds 4096 and bpd answers at
 * most 4096, so a window keeping twice that has everything a pull can say plus what fell off
 * since, and an animation left watching for an hour cannot grow the IDE without limit. The oldest
 * are let go and the count is shown, as every other loss is.
 *
 * ## the one session
 *
 * There is one link at a time — the last bpd session to start — and every call that carries a
 * session's data names its link, so a session ending after another has begun cannot clear the
 * newer one's records, and a pull answering after its session ended cannot bring it back. Pulls
 * carry a ticket as well, so a stale answer cannot overwrite a newer one's.
 *
 * ## the watch
 *
 * The preference is the user's; whether bpd is streaming is bpd's to say. The watch is sent when
 * the adapter is ready ([adapterReady], before the program has run a line — bpd accepts a watch
 * before the runtime is imported), and sent again at a stop and after a pull for as long as the
 * preference is on and bpd has not confirmed. A refusal of a watch is never the window's state: it
 * is said once, as a notification and in the toolbar, and the toggle shows what bpd last confirmed.
 *
 * ## refusals
 *
 * bpd refuses a program with no compose runtime, or one whose tracing is off, in a sentence. That
 * is the ordinary case for most programs, so it is logged at debug level once per session and
 * shown in the window as the reason there is nothing to show — never as an error dialog. No answer
 * at all — a timeout, a failure that is not a refusal — is a state of its own with its own
 * sentence, never "nothing has run".
 */
@Service(Service.Level.PROJECT)
internal class ByRecompositionSession(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    /** What the window has to say about the session, beside the records. */
    sealed interface State {
        /** No bpd session has started, or the last one has ended. */
        data object NoSession : State

        data class Live(
            /** Whether the program is held at a stop. */
            val paused: Boolean = false,
            /** Whether a pull has answered in this session. */
            val pulled: Boolean = false,
            /** False when bpd answered that every runtime has tracing off. */
            val tracing: Boolean = true,
            /** bpd's sentence when it refused the last pull, or null. */
            val refusal: String? = null,
            /** Why the last pull got no answer — a timeout, no adapter — or null once one has answered. */
            val unanswered: String? = null,
            /** What bpd last confirmed about watching, or null before it has said. */
            val watching: Boolean? = null,
            /** Why the last watch request did not confirm — bpd's refusal, or no answer — or null once one has. */
            val watchProblem: String? = null,
            /** Records that fell off the front of the ring, per the last pull. */
            val dropped: Long = 0,
            /** Records in the last pull this build could not read. */
            val unreadable: Int = 0,
            /** The oldest held records this window let go to stay under [HELD_LIMIT]. */
            val letGo: Long = 0,
        ) : State
    }

    private val lock = Any()

    /** Every record held, oldest first. Appended to under [lock]; read through [records]. */
    private val held = ArrayDeque<ByRecord>()

    /** [held] as last read, reused until the next change so a render does not copy per event. */
    private var snapshot: List<ByRecord>? = null

    /** A snapshot of every record held, oldest first. Safe to keep; never changes underneath. */
    val records: List<ByRecord>
        get() = synchronized(lock) { snapshot ?: held.toList().also { snapshot = it } }

    val isEmpty: Boolean
        get() = synchronized(lock) { held.isEmpty() }

    @Volatile
    var state: State = State.NoSession
        private set

    @Volatile
    private var link: ByRecompositionLink? = null

    /** The margin labels of the latest frame, by normalised file path; empty unless paused. */
    @Volatile
    private var labels: Map<String, List<ByMarginLabel>> = emptyMap()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val notifyPending = AtomicBoolean(false)

    /** Numbered so an answer can be told from a newer request's. */
    private val pullTickets = AtomicLong()
    private val watchTickets = AtomicLong()

    /** The ticket of the last pull answer applied; an older ticket's answer is stale. Under [lock]. */
    private var pullApplied = 0L
    private var watchApplied = 0L

    /** Whether a re-send of the watch is on its way, so a stop and its pull do not send two. */
    private val watchResending = AtomicBoolean(false)

    /** Sentences already logged or announced this session, so each is said once. */
    private val reported = HashSet<String>()

    /** Nothing to release: this exists so listeners can be tied to the service's lifetime. */
    override fun dispose() = Unit

    private val enabled: Boolean
        get() = BasedPythonSettings.getInstance(project).debuggerRecompositions

    /**
     * Whether bpd is asked to stream records as they happen.
     *
     * A per-project preference, kept across sessions: someone watching one run wants to watch the
     * next. Sent to bpd when a session's adapter is ready ([adapterReady]), whenever it changes
     * during a session ([setWatching]), and again at a stop or after a pull until bpd confirms.
     */
    val watching: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(WATCH_KEY, false)

    /** Registers [listener], called on the EDT after every change, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    // ---- the session's lifetime ------------------------------------------

    /** A bpd session has started; forget the previous one's records. */
    fun sessionStarted(link: ByRecompositionLink) {
        synchronized(lock) {
            this.link = link
            held.clear()
            snapshot = null
            state = State.Live()
            reported.clear()
            pullApplied = 0L
            watchApplied = 0L
            watchResending.set(false)
            relabel()
        }
        fireNow()
    }

    /** The session behind [link] has ended; a newer session's records are left alone. */
    fun sessionEnded(link: ByRecompositionLink) {
        synchronized(lock) {
            if (this.link !== link) return
            this.link = null
            held.clear()
            snapshot = null
            state = State.NoSession
            reported.clear()
            relabel()
        }
        fireNow()
    }

    /**
     * The program stopped: label the latest frame from what is already held, make sure bpd is
     * watching if it should be, and pull the ring.
     *
     * Labelled now rather than only once the pull lands, because what is held is already true —
     * a watch has appended it, or the last stop pulled it — and a pull that is refused or late
     * must not leave a stop unlabelled. The pull's answer relabels again once it is merged in.
     *
     * Nothing is asked while the setting is off: turned off mid-session, this is where it takes
     * effect for the requests, as [settingChanged] is for the labels.
     */
    fun paused(link: ByRecompositionLink) {
        synchronized(lock) {
            if (this.link !== link) return
            state = live().copy(paused = true)
            relabel()
        }
        fireNow()
        if (!enabled) return
        resendWatch(link)
        pull()
    }

    /** The program runs on: the labels describe a stop that has gone, the records stay. */
    fun resumed(link: ByRecompositionLink) {
        synchronized(lock) {
            if (this.link !== link) return
            state = live().copy(paused = false)
            relabel()
        }
        fireNow()
    }

    /**
     * The setting was turned on or off. Off takes every margin label down now: the pass that
     * would have removed them no longer runs once its factory declines, so the labels are removed
     * from the editors directly rather than left for a daemon run that never comes.
     */
    fun settingChanged() {
        synchronized(lock) { relabel() }
        fireNow()
    }

    /**
     * The adapter has answered `initialize` and is about to be told to run.
     *
     * Called inside the command that also sends `bpd/understands`, which is what makes a watch that
     * was on before the session began see the very first frame: the request goes out ahead of
     * `configurationDone`, so nothing has run yet. bpd accepts a watch before the program has
     * imported the runtime — watching is an interest in records to come. A bpd that refuses it
     * anyway is told again at the first stop ([paused]).
     */
    suspend fun adapterReady(server: ByDebugProtocolServer) {
        if (!enabled || !watching) return
        val ticket = watchTickets.incrementAndGet()
        val answer = try {
            server.watchRecompositions(ByWatchRecompositionsArguments(on = true)).await()
                ?.let { ByRecompositionAnswer.Answered(it) }
                ?: ByRecompositionAnswer.noBody()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val refusal = ByRecompositionRequests.refusalOf(e)
            if (refusal != null) {
                ByRecompositionAnswer.Refused(refusal)
            } else {
                LOG.warn("bpd/watchRecompositions failed at start", e)
                ByRecompositionAnswer.failed(e)
            }
        }
        takeWatch(link ?: return, ticket, answer)
    }

    // ---- what the window asks for ----------------------------------------

    /** Read the ring now. Off the EDT; the answer is published when it arrives. */
    fun pull() {
        val link = link ?: return
        val ticket = pullTickets.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            when (val answer = link.pull()) {
                is ByRecompositionAnswer.Answered -> publish(link, ByRecompositions.parseAnswer(answer.body), ticket)
                is ByRecompositionAnswer.Refused -> refused(link, answer.sentence, ticket)
                is ByRecompositionAnswer.Unavailable -> unanswered(link, answer.why, ticket)
            }
        }
    }

    /** Turn the stream of events on or off, for this session and the ones after it. */
    fun setWatching(on: Boolean) {
        PropertiesComponent.getInstance(project).setValue(WATCH_KEY, on, false)
        fireNow()
        val link = link ?: return
        sendWatch(link, on)
    }

    /** Forget every record shown. bpd's own ring is untouched, and the next pull refills this. */
    fun clear() {
        synchronized(lock) {
            held.clear()
            snapshot = null
            state = live().copy(letGo = 0)
            relabel()
        }
        fireNow()
    }

    // ---- what arrives -----------------------------------------------------

    /**
     * One event, as bpd pushed it while watching: its record, and what the stream dropped before
     * it. Appended under the lock, never copied — this runs on the DAP reader thread, which also
     * has to deliver `stopped`. Listeners hear about it at most every [COALESCE_MS].
     *
     * A drop is kept as a [ByRecord.Gap] at the place it happened, filed under the frame of the
     * record that carried the count, so the tree can say so there and count it.
     */
    fun append(event: ByEvent) {
        synchronized(lock) {
            if (link == null || !enabled) return
            val record = event.record
            if (event.droppedBefore > 0) {
                val at = record ?: held.lastOrNull()
                hold(ByRecord.Gap(runtime = at?.runtime ?: 0, frame = at?.frame ?: 0, dropped = event.droppedBefore))
            }
            if (record != null) hold(record)
        }
        fireCoalesced()
    }

    /**
     * A pull's answer: its records are merged in and the labels of the latest frame redrawn.
     *
     * Named by its [link] and its [ticket] so an answer landing after its session ended, after a
     * newer session started, or after a newer pull's answer, is dropped. Public for tests, which
     * drive the service without an adapter; a test's answer takes the next ticket.
     */
    fun publish(link: ByRecompositionLink, reply: ByRecompositions.Reply, ticket: Long = pullTickets.incrementAndGet()) {
        when (reply) {
            is ByRecompositions.Reply.Unreadable -> refused(link, reply.why, ticket)
            is ByRecompositions.Reply.Read -> {
                val answer = reply.answer
                synchronized(lock) {
                    if (this.link !== link || ticket < pullApplied) return
                    pullApplied = ticket
                    replaceHeld(ByRecordMerge.merge(held, answer.kept))
                    state = live().copy(
                        pulled = true,
                        tracing = answer.tracing,
                        refusal = null,
                        unanswered = null,
                        dropped = answer.dropped,
                        unreadable = answer.unreadable,
                    )
                    relabel()
                }
                fireNow()
                if (enabled) resendWatch(link)
            }
        }
    }

    /** bpd declined a pull, in a sentence; said once per session in the log, and shown in the window. */
    fun refused(link: ByRecompositionLink, sentence: String, ticket: Long = pullTickets.incrementAndGet()) {
        synchronized(lock) {
            if (this.link !== link || ticket < pullApplied) return
            pullApplied = ticket
            if (reported.add(sentence)) LOG.debug("bpd refused a recompositions request: $sentence")
            state = live().copy(refusal = sentence, unanswered = null)
        }
        fireNow()
    }

    /** A pull got no answer: a state of its own, so the window never says "nothing has run" for it. */
    fun unanswered(link: ByRecompositionLink, why: String, ticket: Long = pullTickets.incrementAndGet()) {
        synchronized(lock) {
            if (this.link !== link || ticket < pullApplied) return
            pullApplied = ticket
            if (reported.add(why)) LOG.info("bpd/recompositions got no answer: $why")
            state = live().copy(unanswered = why)
        }
        fireNow()
    }

    // ---- the watch ------------------------------------------------------------

    /**
     * Send the watch again if the preference is on and bpd has not confirmed it — once per stop
     * and once per pull, never while one is already on its way.
     */
    private fun resendWatch(link: ByRecompositionLink) {
        if (!watching || (state as? State.Live)?.watching == true) return
        if (!watchResending.compareAndSet(false, true)) return
        sendWatch(link, on = true, onDone = { watchResending.set(false) })
    }

    private fun sendWatch(link: ByRecompositionLink, on: Boolean, onDone: () -> Unit = {}) {
        val ticket = watchTickets.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                takeWatch(link, ticket, link.watch(on))
            } finally {
                onDone()
            }
        }
    }

    private fun takeWatch(link: ByRecompositionLink, ticket: Long, answer: ByRecompositionAnswer) {
        when (answer) {
            is ByRecompositionAnswer.Answered -> confirmWatching(link, ticket, answer.body)
            is ByRecompositionAnswer.Refused -> watchProblem(link, ticket, answer.sentence)
            is ByRecompositionAnswer.Unavailable -> watchProblem(link, ticket, answer.why)
        }
    }

    private fun confirmWatching(link: ByRecompositionLink, ticket: Long, body: JsonObject) {
        val watching = body.get("watching")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        synchronized(lock) {
            if (this.link !== link || ticket < watchApplied) return
            watchApplied = ticket
            state = live().copy(watching = watching, watchProblem = null)
        }
        fireNow()
    }

    /**
     * bpd did not confirm a watch. Not the window's state — the records and the pull are
     * untouched by it — but the toolbar says so beside the toggle, and a notification says so
     * once per session per sentence, because a watch that was asked for and is not on is exactly
     * what someone waiting for the first frame needs to hear.
     */
    private fun watchProblem(link: ByRecompositionLink, ticket: Long, sentence: String) {
        val first: Boolean
        synchronized(lock) {
            if (this.link !== link || ticket < watchApplied) return
            watchApplied = ticket
            first = reported.add("watch: $sentence")
            if (first) LOG.debug("bpd did not confirm a recompositions watch: $sentence")
            state = live().copy(watchProblem = sentence)
        }
        fireNow()
        if (first) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    BasedPythonBundle.message("recompose.notification.watch.title"),
                    sentence,
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }

    // ---- the held list ---------------------------------------------------------

    /** Under [lock]: append one record, letting the oldest go past [HELD_LIMIT]. */
    private fun hold(record: ByRecord) {
        held.addLast(record)
        snapshot = null
        if (held.size > HELD_LIMIT) {
            held.removeFirst()
            state = live().let { it.copy(letGo = it.letGo + 1) }
        }
    }

    /** Under [lock]: replace what is held with [records], letting the oldest go past [HELD_LIMIT]. */
    private fun replaceHeld(records: List<ByRecord>) {
        val over = records.size - HELD_LIMIT
        held.clear()
        if (over > 0) {
            held.addAll(records.subList(over, records.size))
            state = live().let { it.copy(letGo = it.letGo + over) }
        } else {
            held.addAll(records)
        }
        snapshot = null
    }

    /** The live state, or a fresh one for a state change that arrived without a session start. */
    private fun live(): State.Live = state as? State.Live ?: State.Live()

    // ---- the margin labels -------------------------------------------------

    /** What the editor draws on [file]'s composable definitions at the stop the program is held at. */
    fun labelsFor(file: VirtualFile): List<ByMarginLabel> = labels[normalise(file.path)].orEmpty()

    /**
     * Recompute the labels from the latest frame, and bring the editors that show them up to
     * date. Under [lock].
     */
    private fun relabel() {
        val paused = (state as? State.Live)?.paused == true
        val next = if (paused && enabled) {
            ByRecompositionTree.labels(held).groupBy { normalise(it.file) }
        } else {
            emptyMap()
        }
        val stale = labels.keys
        labels = next
        redraw(paths = stale + next.keys, cleared = stale - next.keys)
    }

    /**
     * Bring the open editors of these files up to date.
     *
     * A file that has labels gets a daemon run, which draws them through the pass. A file that
     * has none any more ([cleared]) has its labels removed here, directly: the pass is what would
     * remove them, and a pass does not run for a setting that is off or an editor the daemon has
     * no reason to visit — so leaving it to the pass would leave a session's last labels on
     * screen. The daemon is restarted for those files too, which cancels a pass already running
     * against the labels that have just gone.
     *
     * The open editors rather than the file system: a label only matters where it can be seen,
     * and asking the editors also finds a file the local file system would not, which is what a
     * test's in-memory file is.
     */
    private fun redraw(paths: Set<String>, cleared: Set<String>) {
        if (paths.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            if (cleared.isNotEmpty()) {
                val documents = FileDocumentManager.getInstance()
                for (editor in EditorFactory.getInstance().allEditors) {
                    if (editor.project != project) continue
                    val file = documents.getFile(editor.document) ?: continue
                    if (normalise(file.path) in cleared) ByRecompositionMarks.clear(editor)
                }
            }
            val psi = PsiManager.getInstance(project)
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            for (file in FileEditorManager.getInstance(project).openFiles) {
                if (!file.isValid || normalise(file.path) !in paths) continue
                psi.findFile(file)?.let(daemon::restart)
            }
        }, project.disposed)
    }

    // ---- telling the window -----------------------------------------------

    private fun fireNow() {
        ApplicationManager.getApplication().invokeLater({ listeners.forEach { it() } }, project.disposed)
    }

    /**
     * One notification for a burst of events.
     *
     * A frame under watch is a handful of records in a few microseconds, and rebuilding a tree per
     * record would spend the EDT on nothing the user can see. The first event schedules a
     * notification; the rest ride along with it.
     */
    private fun fireCoalesced() {
        if (!notifyPending.compareAndSet(false, true)) return
        scope.launch {
            delay(COALESCE_MS)
            notifyPending.set(false)
            fireNow()
        }
    }

    companion object {
        fun getInstance(project: Project): ByRecompositionSession = project.service()

        private val LOG = Logger.getInstance(ByRecompositionSession::class.java)

        private const val WATCH_KEY = "basedpython.recompositions.watch"

        /** The plugin's balloon group, registered in plugin.xml. */
        private const val NOTIFICATION_GROUP = "basedpython"

        /** How often, at most, a stream of events reaches the window. */
        const val COALESCE_MS: Long = 100

        /**
         * The most records held at once: twice the runtime's ring and twice what a pull can carry,
         * so nothing a pull says is ever let go for want of room, and a long watch stays bounded.
         */
        const val HELD_LIMIT: Int = 8_192

        /**
         * A path as both bpd and the editor might spell it.
         *
         * System-independent separators, and case folded where the file system does not
         * distinguish it: bpd reports the path the program's source map holds, the editor the one
         * the VFS holds, and the two can differ in exactly those ways.
         */
        fun normalise(path: String): String {
            val independent = FileUtil.toSystemIndependentName(path)
            return if (SystemInfo.isFileSystemCaseSensitive) independent else independent.lowercase()
        }
    }
}
