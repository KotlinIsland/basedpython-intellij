package dev.basedpython.pycharm.debug.recompose

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Why a basedpython-ui scope ran, as bpd reads it out of the runtime's trace ring.
 *
 * ## what this is
 *
 * The runtime keeps a bounded record of every scope run, every state write and every frame
 * (`basedpython_ui.runtime.Trace`; the layout is `docs/development/trace-protocol.md` in
 * basedpython-ui). bpd reads it at a stop for `bpd/recompositions`, forwards records while a client
 * is watching as `bpd/recomposition` events, and maps every location through the build's source
 * map on the way — so what arrives here is `.by` files and lines, with the generated location
 * beside each. The json shapes are bpd's serde output, fixed in the wire document both sides were
 * written from, and these classes are that document read field by field.
 *
 * ## parsing
 *
 * Total over any json, the [dev.basedpython.pycharm.debug.ByMoved] rule: every accessor checks the
 * *kind* of what it found, a field of the wrong type is absent rather than an exception, and a
 * record this cannot read costs that record and nothing else. An unknown `record` or `cause` tag is
 * a record from a newer bpd, and a session must not end — or a tree go blank — because one shape
 * changed. The count of what was declined is kept ([Answer.unreadable]) so the window can say so
 * rather than looking complete.
 *
 * No Gson-mapped classes, for the reason [dev.basedpython.pycharm.debug.dfa.ByDataFlowFacts] has
 * none: the vocabulary is bpd's, and a POJO here would be a second copy that has to agree.
 */
internal object ByRecompositions {

    /** The event bpd pushes while watching, which [dev.basedpython.pycharm.debug.ByDebugProtocolServer.understands] names back. */
    const val EVENT: String = "bpd/recomposition"

    /** The one trace format this reads; the runtime's `TRACE_FORMAT`. */
    const val FORMAT: Int = 1

    /** What `bpd/recompositions` answered, read. */
    sealed interface Reply {
        /** An answer in a format this reads, with whatever records in it could be read. */
        data class Read(val answer: Answer) : Reply

        /** An answer this cannot read at all — a format this plugin does not know, or no body. */
        data class Unreadable(val why: String) : Reply
    }

    /**
     * The answer to `bpd/recompositions`.
     *
     * @param runtimes how many runtimes the program held; records carry their runtime's index
     * @param tracing false when every runtime has `trace = None`, in which case [kept] is empty
     * @param kept every record that could be read, oldest first
     * @param dropped what fell off the front — the runtime's own count plus what bpd left out
     * @param unreadable records in the answer this could not read, which is never a reason to hide
     *   the ones it could
     */
    data class Answer(
        val runtimes: Int,
        val tracing: Boolean,
        val kept: List<ByRecord>,
        val dropped: Long,
        val unreadable: Int,
    )

    /** Read an answer body, or say why it cannot be. */
    fun parseAnswer(body: JsonObject?): Reply {
        if (body == null) return Reply.Unreadable("the debug adapter answered with no body")
        val format = body.int("format")
            ?: return Reply.Unreadable("the answer names no trace format")
        if (format != FORMAT) {
            return Reply.Unreadable("the program's basedpython_ui writes trace format $format and this plugin reads $FORMAT")
        }
        val records = body.obj("records")
        var unreadable = 0
        val kept = ArrayList<ByRecord>()
        for (element in records?.array("kept") ?: emptyList<JsonElement>()) {
            val record = element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseRecord)
            if (record == null) unreadable++ else kept += record
        }
        return Reply.Read(
            Answer(
                runtimes = body.int("runtimes") ?: 1,
                tracing = body.bool("tracing") ?: true,
                kept = kept,
                dropped = records?.long("dropped") ?: 0L,
                unreadable = unreadable,
            ),
        )
    }

    /**
     * Read a `bpd/recomposition` event body — `{ "record": <record>, "dropped_before": N }` — or
     * null when there is no body.
     *
     * `dropped_before` is optional and 0 when absent: an older bpd that did not count sends the
     * record alone. It is read even when the record itself cannot be, because what was lost before
     * an unreadable record is still lost.
     */
    fun parseEvent(body: JsonObject?): ByEvent? {
        if (body == null) return null
        val record = body.obj("record")?.let(::parseRecord)
        val droppedBefore = body.long("dropped_before")?.coerceAtLeast(0L) ?: 0L
        if (record == null && droppedBefore == 0L) return null
        return ByEvent(record, droppedBefore)
    }

    /**
     * Read one record, or null when it is not one this can use.
     *
     * The tag decides which shape is read, and nothing is inferred from which fields happen to be
     * present: a shape change is then a missing record rather than a silently wrong one.
     */
    fun parseRecord(obj: JsonObject): ByRecord? {
        val runtime = obj.int("runtime") ?: return null
        val frame = obj.long("frame") ?: return null
        return when (obj.string("record")) {
            "run" -> ByRecord.Run(
                runtime = runtime,
                frame = frame,
                scope = obj.long("scope") ?: return null,
                parent = obj.long("parent"),
                name = obj.string("name") ?: return null,
                defined = obj.obj("defined")?.let(::parseLocation),
                called = obj.obj("called")?.let(::parseLocation),
                key = obj.scalar("key"),
                origin = obj.string("origin") ?: return null,
                causes = obj.causes("causes") ?: return null,
                skipped = obj.array("skipped")?.mapNotNull { it.longOrNull() }.orEmpty(),
                disposed = obj.array("disposed")?.mapNotNull { element ->
                    val child = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    ByDisposed(
                        scope = child.long("scope") ?: return@mapNotNull null,
                        name = child.string("name") ?: return@mapNotNull null,
                        key = child.scalar("key"),
                    )
                }.orEmpty(),
                elapsedNs = obj.long("elapsed_ns"),
            )

            "write" -> ByRecord.Write(
                runtime = runtime,
                frame = frame,
                cause = obj.obj("cause")?.let(::parseCause) ?: return null,
            )

            "frame" -> ByRecord.Frame(
                runtime = runtime,
                frame = frame,
                runs = obj.int("runs") ?: return null,
                skips = obj.int("skips") ?: return null,
                composeNs = obj.long("compose_ns") ?: return null,
                commitNs = obj.long("commit_ns") ?: return null,
            )

            "error" -> ByRecord.Error(
                runtime = runtime,
                frame = frame,
                scope = obj.long("scope") ?: return null,
                name = obj.string("name") ?: return null,
                error = obj.string("error") ?: return null,
                keptPrevious = obj.bool("kept_previous") ?: return null,
            )

            "refused" -> ByRecord.Refused(
                runtime = runtime,
                frame = frame,
                scope = obj.long("scope") ?: return null,
                name = obj.string("name") ?: return null,
                what = obj.string("what") ?: return null,
            )

            // A kind a newer bpd grew. Reading it as the nearest one would invent a meaning
            else -> null
        }
    }

    /**
     * Read one cause, or null when it is not one this can use.
     *
     * Recursive through `dirty` and `derived`, and total the same way: an unreadable inner cause
     * makes the outer one unreadable, because a `dirty` whose reasons are half gone is not the
     * fact the runtime recorded.
     */
    fun parseCause(obj: JsonObject): ByCause? = when (obj.string("cause")) {
        "created" -> ByCause.Created
        "invalidated" -> ByCause.Invalidated
        "inline" -> ByCause.Inline
        "uncommitted" -> ByCause.Uncommitted
        "args" -> ByCause.Args(
            parameter = obj.string("parameter") ?: return null,
            old = obj.string("old"),
            new = obj.string("new"),
            compared = obj.bool("compared") ?: return null,
        )

        "recovery" -> ByCause.Recovery(error = obj.string("error") ?: return null)
        "dirty" -> ByCause.Dirty(causes = obj.causes("causes") ?: return null)
        "state" -> ByCause.State(
            cell = obj.long("cell") ?: return null,
            kind = obj.string("kind") ?: return null,
            op = obj.string("op") ?: return null,
            at = obj.scalar("at"),
            old = obj.string("old"),
            new = obj.string("new"),
            declared = obj.obj("declared")?.let(::parseLocation),
            declaredName = obj.string("declared_name"),
            written = obj.obj("written")?.let(::parseLocation) ?: return null,
            thread = obj.long("thread"),
            posted = obj.bool("posted") ?: return null,
            readers = obj.int("readers") ?: return null,
        )

        "derived" -> ByCause.Derived(
            derived = obj.long("derived") ?: return null,
            declared = obj.obj("declared")?.let(::parseLocation),
            declaredName = obj.string("declared_name"),
            old = obj.string("old"),
            new = obj.string("new"),
            changed = obj.bool("changed") ?: return null,
            because = obj.obj("because")?.let(::parseCause) ?: return null,
        )

        else -> null
    }

    /**
     * A location, or null without a file and a line.
     *
     * `generated` is read one level deep and shown, never opened — `file`/`line` are already the
     * `.by` place when the map covers the generated file, and the generated place itself when it
     * does not, which is the same mapping every frame bpd reports goes through.
     */
    fun parseLocation(obj: JsonObject): ByTraceLocation? {
        val file = obj.string("file") ?: return null
        val line = obj.int("line") ?: return null
        val generated = obj.obj("generated")?.let { g ->
            val gFile = g.string("file") ?: return@let null
            val gLine = g.int("line") ?: return@let null
            ByGeneratedLocation(gFile, gLine)
        }
        return ByTraceLocation(file = file, line = line, generated = generated, reason = obj.string("reason"))
    }

    /** Every cause in the array under [name], or null when the field is missing or any one is unreadable. */
    private fun JsonObject.causes(name: String): List<ByCause>? {
        val array = array(name) ?: return null
        val causes = ArrayList<ByCause>(array.size())
        for (element in array) {
            val cause = element.takeIf { it.isJsonObject }?.asJsonObject?.let(::parseCause) ?: return null
            causes += cause
        }
        return causes
    }

    /** `null | int | string`, which is what a scope key and an op's index/key both are. */
    private fun JsonObject.scalar(name: String): ByTraceScalar? {
        val primitive = primitive(name) ?: return null
        return when {
            primitive.isNumber -> ByTraceScalar.Number(primitive.asLong)
            primitive.isString -> ByTraceScalar.Text(primitive.asString)
            else -> null
        }
    }

    private fun JsonElement.longOrNull(): Long? =
        takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.primitive(name: String) =
        get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.obj(name: String) = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String) = get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.string(name: String) = primitive(name)?.takeIf { it.isString }?.asString

    private fun JsonObject.int(name: String) = primitive(name)?.takeIf { it.isNumber }?.asInt

    private fun JsonObject.long(name: String) = primitive(name)?.takeIf { it.isNumber }?.asLong

    private fun JsonObject.bool(name: String) = primitive(name)?.takeIf { it.isBoolean }?.asBoolean
}

/**
 * A place in the program as bpd reports one: the `.by` file and line when the build's source map
 * covers the generated file, else the generated place itself.
 *
 * @param generated the location the interpreter actually ran; null only when [file] already is it
 * @param reason why a mapped generated line kept its generated location, the way a frame says it
 */
internal data class ByTraceLocation(
    val file: String,
    val line: Int,
    val generated: ByGeneratedLocation?,
    val reason: String?,
)

/** The generated `.py` place behind a [ByTraceLocation]. Shown, never opened. */
internal data class ByGeneratedLocation(val file: String, val line: Int)

/** A value that is `null`, an integer or a string on the wire: a scope key, or the index/key an op touched. */
internal sealed interface ByTraceScalar {
    data class Number(val value: Long) : ByTraceScalar
    data class Text(val value: String) : ByTraceScalar

    /** As the program would spell it: a number bare, a string quoted. */
    fun render(): String = when (this) {
        is Number -> value.toString()
        is Text -> "'$value'"
    }
}

/** A child disposed after a run because the run did not reach it. */
internal data class ByDisposed(val scope: Long, val name: String, val key: ByTraceScalar?)

/**
 * Why a scope ran — the runtime's cause tuple, one class per tag.
 *
 * The `old`/`new` slots of a state, derived or args cause are **rendered text**, never program
 * objects: bpd renders an exact builtin scalar as itself, a container by kind and size
 * (`list[3]`), and anything else as `a Todo`, without running program code. They are shown as
 * they arrive.
 */
internal sealed interface ByCause {
    /**
     * First composition of a new scope.
     *
     * A key change has no cause of its own: the new scope is `created`, and the old key is named
     * under the parent's `disposed` once the parent's run has ended and the runtime knows the key
     * was really given up.
     */
    data object Created : ByCause

    /** `Runtime.invalidate` was called with no cause. */
    data object Invalidated : ByCause

    /** The scope takes a content block, so it re-runs whenever its parent runs. */
    data object Inline : ByCause

    /** The scope was created in a frame whose commit did not happen. */
    data object Uncommitted : ByCause

    /**
     * The parent ran and this argument differed.
     *
     * @param compared false when the argument's type is unstable and it was never compared at all
     */
    data class Args(val parameter: String, val old: String?, val new: String?, val compared: Boolean) : ByCause

    /** The previous run raised [error]. */
    data class Recovery(val error: String) : ByCause

    /** The scope was already dirty when its parent reached it, for these reasons. */
    data class Dirty(val causes: List<ByCause>) : ByCause

    /**
     * A cell the scope read changed.
     *
     * @param kind `state`, `list`, `dict` or `ambient`
     * @param op `set`, `append`, `insert`, `remove`, `pop`, `clear`, `put`, `delete` or `provide`
     * @param at the index or key the op touched; null for a whole-cell write
     * @param declared where the cell was created, and [declaredName] the name it was bound to;
     *   both null for a cell created outside composition
     * @param written the frame that called the public mutator
     * @param posted the write came from another thread and was applied at the next frame
     * @param readers trackers notified; 0 means nothing depended on the cell
     */
    data class State(
        val cell: Long,
        val kind: String,
        val op: String,
        val at: ByTraceScalar?,
        val old: String?,
        val new: String?,
        val declared: ByTraceLocation?,
        val declaredName: String?,
        val written: ByTraceLocation,
        val thread: Long?,
        val posted: Boolean,
        val readers: Int,
    ) : ByCause

    /**
     * A derived the scope read recomputed.
     *
     * @param changed false when the value compared equal and no reader was invalidated
     * @param because the state or derived cause that made it recompute
     */
    data class Derived(
        val derived: Long,
        val declared: ByTraceLocation?,
        val declaredName: String?,
        val old: String?,
        val new: String?,
        val changed: Boolean,
        val because: ByCause,
    ) : ByCause
}

/**
 * One `bpd/recomposition` event: a record, and how many records bpd's outbound queue dropped
 * before it because the program outran the debugger.
 *
 * @param record the record, or null when this build could not read it
 * @param droppedBefore records lost between the previous event and this one; 0 when none
 */
internal data class ByEvent(val record: ByRecord?, val droppedBefore: Long)

/**
 * One record of the trace ring, tagged by what happened — plus [Gap], which is not the runtime's
 * but the stream's.
 */
internal sealed interface ByRecord {
    /** The runtime's index in `live_runtimes`; a program normally has one. */
    val runtime: Int

    /** The runtime's frame counter when the record was made. */
    val frame: Long

    /** A scope ran. */
    data class Run(
        override val runtime: Int,
        override val frame: Long,
        val scope: Long,
        val parent: Long?,
        val name: String,
        val defined: ByTraceLocation?,
        val called: ByTraceLocation?,
        val key: ByTraceScalar?,
        /** `first` (created this run), `self` (popped from the dirty heap) or `parent` (its parent ran it). */
        val origin: String,
        /** Never empty on the wire; a run always has a reason. */
        val causes: List<ByCause>,
        /** Children this run emitted as references instead of running. */
        val skipped: List<Long>,
        /** Children disposed after this run because it did not reach them. */
        val disposed: List<ByDisposed>,
        val elapsedNs: Long?,
    ) : ByRecord

    /** A state write happened, or a derived recomputed. */
    data class Write(
        override val runtime: Int,
        override val frame: Long,
        val cause: ByCause,
    ) : ByRecord

    /** A frame finished. */
    data class Frame(
        override val runtime: Int,
        override val frame: Long,
        val runs: Int,
        val skips: Int,
        val composeNs: Long,
        val commitNs: Long,
    ) : ByRecord

    /** A scope raised. */
    data class Error(
        override val runtime: Int,
        override val frame: Long,
        val scope: Long,
        val name: String,
        val error: String,
        /** Whether a committed subtree stayed on screen. */
        val keptPrevious: Boolean,
    ) : ByRecord

    /** A write during composition was refused. */
    data class Refused(
        override val runtime: Int,
        override val frame: Long,
        val scope: Long,
        val name: String,
        /** The cell kind and op that was refused — `state set`. */
        val what: String,
    ) : ByRecord

    /**
     * Records bpd's stream dropped before the record that follows this one, because the program
     * outran the debugger. Never on the wire as a record: it is the `dropped_before` count of an
     * event, kept in the held list at the place the loss happened so the tree can say so there,
     * and filed under the frame of the record that carried the count.
     */
    data class Gap(
        override val runtime: Int,
        override val frame: Long,
        val dropped: Long,
    ) : ByRecord
}
