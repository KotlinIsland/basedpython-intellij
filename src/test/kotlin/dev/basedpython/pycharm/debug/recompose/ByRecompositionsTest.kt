package dev.basedpython.pycharm.debug.recompose

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading bpd's `bpd/recompositions` answer and `bpd/recomposition` event.
 *
 * Every payload here is written from the wire document both sides were built from
 * (`bpd-recompositions-wire.md`), field for field and value for value — bpd did not exist to
 * capture from when this was written, so the document is the contract, and these payloads are the
 * document rather than the parser's own idea of it.
 */
class ByRecompositionsTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    /** The wire document's run record, with its `causes` filled from the document's cause table. */
    private val run = """
        { "record": "run", "runtime": 0, "frame": 3, "scope": 5, "parent": 0, "name": "Counter",
          "defined": { "file": "/app/examples/counter.by", "line": 9,
                       "generated": { "file": "/tmp/build/examples/counter.py", "line": 41 } },
          "called": { "file": "/app/examples/counter.by", "line": 24,
                      "generated": { "file": "/tmp/build/examples/counter.py", "line": 90 } },
          "key": null,
          "origin": "self",
          "causes": [ $stateCause ],
          "skipped": [7, 8],
          "disposed": [ { "scope": 9, "name": "Row", "key": 2 } ],
          "elapsed_ns": 12345 }
    """

    private val stateCause
        get() = """
        { "cause": "state", "cell": 4401, "kind": "state", "op": "set", "at": null,
          "old": "0", "new": "2",
          "declared": { "file": "/app/examples/counter.by", "line": 12,
                        "generated": { "file": "/tmp/build/examples/counter.py", "line": 44 } },
          "declared_name": "count",
          "written": { "file": "/app/examples/counter.by", "line": 14,
                       "generated": { "file": "/tmp/build/examples/counter.py", "line": 47 } },
          "thread": 8674, "posted": false, "readers": 1 }
        """

    @Test
    fun `a run record is read whole`() {
        val record = ByRecompositions.parseRecord(obj(run))
        assertInstanceOf(ByRecord.Run::class.java, record)
        record as ByRecord.Run
        assertEquals(0, record.runtime)
        assertEquals(3L, record.frame)
        assertEquals(5L, record.scope)
        assertEquals(0L, record.parent)
        assertEquals("Counter", record.name)
        assertEquals(
            ByTraceLocation("/app/examples/counter.by", 9, ByGeneratedLocation("/tmp/build/examples/counter.py", 41), null),
            record.defined,
        )
        assertEquals(24, record.called?.line)
        assertNull(record.key)
        assertEquals("self", record.origin)
        assertEquals(1, record.causes.size)
        assertEquals(listOf(7L, 8L), record.skipped)
        assertEquals(listOf(ByDisposed(9, "Row", ByTraceScalar.Number(2))), record.disposed)
        assertEquals(12345L, record.elapsedNs)
    }

    @Test
    fun `the root's null call site and a string key both read`() {
        val root = obj("""{ "record": "run", "runtime": 0, "frame": 1, "scope": 0, "parent": null, "name": "root",
            "defined": { "file": "/app/a.by", "line": 1, "generated": null }, "called": null, "key": "tab-a",
            "origin": "first", "causes": [ { "cause": "created" } ], "skipped": [], "disposed": [], "elapsed_ns": 1 }""")
        val record = ByRecompositions.parseRecord(root) as ByRecord.Run
        assertNull(record.parent)
        assertNull(record.called)
        assertNull(record.defined?.generated, "no map: the generated place is the `.by` place itself")
        assertEquals(ByTraceScalar.Text("tab-a"), record.key)
        assertEquals("'tab-a'", record.key?.render())
    }

    /** The whole cause table of the wire document, one of each. */
    @Test
    fun `every cause kind is read`() {
        val causes = """[
            { "cause": "created" },
            { "cause": "invalidated" },
            { "cause": "inline" },
            { "cause": "uncommitted" },
            { "cause": "args", "parameter": "step", "old": "1", "new": "2", "compared": true },
            { "cause": "recovery", "error": "boom" },
            { "cause": "dirty", "causes": [ { "cause": "created" }, { "cause": "inline" } ] },
            $stateCause,
            { "cause": "derived", "derived": 77,
              "declared": { "file": "/app/examples/counter.by", "line": 20, "generated": null },
              "declared_name": "total",
              "old": "1", "new": "2", "changed": true, "because": $stateCause }
        ]"""
        val record = ByRecompositions.parseRecord(obj(run.replace("[ $stateCause ]", causes))) as ByRecord.Run
        assertEquals(9, record.causes.size, "the cause table has nine kinds: ${record.causes}")
        val (created, invalidated, inline, uncommitted, args, recovery, dirty, state, derived) =
            record.causes.let { c -> Nine(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]) }

        assertEquals(ByCause.Created, created)
        assertEquals(ByCause.Invalidated, invalidated)
        assertEquals(ByCause.Inline, inline)
        assertEquals(ByCause.Uncommitted, uncommitted)
        assertEquals(ByCause.Args("step", "1", "2", compared = true), args)
        assertEquals(ByCause.Recovery("boom"), recovery)
        assertEquals(ByCause.Dirty(listOf(ByCause.Created, ByCause.Inline)), dirty)

        state as ByCause.State
        assertEquals(4401L, state.cell)
        assertEquals("state", state.kind)
        assertEquals("set", state.op)
        assertNull(state.at)
        assertEquals("0", state.old)
        assertEquals("2", state.new)
        assertEquals(12, state.declared?.line)
        assertEquals("count", state.declaredName)
        assertEquals(14, state.written.line)
        assertEquals(8674L, state.thread)
        assertFalse(state.posted)
        assertEquals(1, state.readers)

        derived as ByCause.Derived
        assertEquals(77L, derived.derived)
        assertEquals("total", derived.declaredName)
        assertEquals(20, derived.declared?.line)
        assertTrue(derived.changed)
        assertEquals(state, derived.because, "`because` is the state cause that made it recompute, read the same way")
    }

    private data class Nine<T>(val a: T, val b: T, val c: T, val d: T, val e: T, val f: T, val g: T, val h: T, val i: T)

    /**
     * There is no `key` cause: a key change is `created` on the new scope plus the old key under
     * the parent's `disposed`, because whether an old key was really given up is known only when
     * the parent's run ends. A bpd that sent one would be writing a shape this does not read, and
     * it costs the record the way any unknown cause does.
     */
    @Test
    fun `a key cause is not a cause this reads`() {
        assertNull(ByRecompositions.parseRecord(obj(run.replace("[ $stateCause ]", """[ { "cause": "key", "old": 2 } ]"""))))
        assertNull(ByRecompositions.parseCause(obj("""{ "cause": "key", "old": "a" }""")))
        val disposed = ByRecompositions.parseRecord(obj(run)) as ByRecord.Run
        assertEquals(listOf(ByDisposed(9, "Row", ByTraceScalar.Number(2))), disposed.disposed, "the old key lives under `disposed`")
    }

    @Test
    fun `an unstable argument and a cell created outside composition read as such`() {
        val causes = """[
            { "cause": "args", "parameter": "draft", "old": "a Draft", "new": "a Draft", "compared": false },
            { "cause": "state", "cell": 1, "kind": "list", "op": "append", "at": null, "old": "None", "new": "'x'",
              "declared": null, "declared_name": null,
              "written": { "file": "/app/a.by", "line": 3, "generated": null },
              "thread": 1, "posted": true, "readers": 0 }
        ]"""
        val record = ByRecompositions.parseRecord(obj(run.replace("[ $stateCause ]", causes))) as ByRecord.Run
        val args = record.causes[0] as ByCause.Args
        assertFalse(args.compared)
        val state = record.causes[1] as ByCause.State
        assertNull(state.declared)
        assertNull(state.declaredName)
        assertTrue(state.posted)
        assertEquals(0, state.readers)
    }

    @Test
    fun `a write, a frame, an error and a refusal are read`() {
        val write = ByRecompositions.parseRecord(obj("""{ "record": "write", "runtime": 0, "frame": 3, "cause": $stateCause }"""))
        assertInstanceOf(ByRecord.Write::class.java, write)
        assertInstanceOf(ByCause.State::class.java, (write as ByRecord.Write).cause)

        val frame = ByRecompositions.parseRecord(
            obj("""{ "record": "frame", "runtime": 0, "frame": 3, "runs": 2, "skips": 4, "compose_ns": 15000, "commit_ns": 300000 }"""),
        )
        assertEquals(ByRecord.Frame(0, 3, runs = 2, skips = 4, composeNs = 15000, commitNs = 300000), frame)

        val error = ByRecompositions.parseRecord(
            obj("""{ "record": "error", "runtime": 0, "frame": 3, "scope": 5, "name": "Flaky", "error": "boom", "kept_previous": true }"""),
        )
        assertEquals(ByRecord.Error(0, 3, scope = 5, name = "Flaky", error = "boom", keptPrevious = true), error)

        val refused = ByRecompositions.parseRecord(
            obj("""{ "record": "refused", "runtime": 0, "frame": 3, "scope": 5, "name": "Bad", "what": "state set" }"""),
        )
        assertEquals(ByRecord.Refused(0, 3, scope = 5, name = "Bad", what = "state set"), refused)
    }

    @Test
    fun `the answer envelope is read`() {
        val reply = ByRecompositions.parseAnswer(
            obj("""{ "format": 1, "runtimes": 1, "tracing": true,
                     "records": { "kept": [ $run, { "record": "frame", "runtime": 0, "frame": 3, "runs": 1, "skips": 2, "compose_ns": 1, "commit_ns": 2 } ],
                                  "dropped": 40 },
                     "mode": { "mode": "non_stop" } }"""),
        )
        assertInstanceOf(ByRecompositions.Reply.Read::class.java, reply)
        val answer = (reply as ByRecompositions.Reply.Read).answer
        assertEquals(1, answer.runtimes)
        assertTrue(answer.tracing)
        assertEquals(2, answer.kept.size)
        assertEquals(40L, answer.dropped)
        assertEquals(0, answer.unreadable)
    }

    @Test
    fun `tracing off is an answer with nothing in it, not a failure`() {
        val reply = ByRecompositions.parseAnswer(
            obj("""{ "format": 1, "runtimes": 1, "tracing": false, "records": { "kept": [], "dropped": 0 }, "mode": {} }"""),
        ) as ByRecompositions.Reply.Read
        assertFalse(reply.answer.tracing)
        assertTrue(reply.answer.kept.isEmpty())
    }

    /** The format is compared before anything else is read, and a mismatch is refused by name. */
    @Test
    fun `a format this does not read is refused by name`() {
        val reply = ByRecompositions.parseAnswer(obj("""{ "format": 2, "records": { "kept": [ $run ] } }"""))
        assertInstanceOf(ByRecompositions.Reply.Unreadable::class.java, reply)
        assertTrue((reply as ByRecompositions.Reply.Unreadable).why.contains("format 2"), reply.why)
        assertInstanceOf(ByRecompositions.Reply.Unreadable::class.java, ByRecompositions.parseAnswer(obj("{}")))
        assertInstanceOf(ByRecompositions.Reply.Unreadable::class.java, ByRecompositions.parseAnswer(null))
    }

    /** The `ByMoved.parse` rule: a kind from a newer bpd costs that record, never the answer. */
    @Test
    fun `an unknown record kind costs that record and is counted`() {
        val reply = ByRecompositions.parseAnswer(
            obj("""{ "format": 1, "records": { "kept": [ { "record": "commit", "runtime": 0, "frame": 3 }, $run, 5 ], "dropped": 0 } }"""),
        ) as ByRecompositions.Reply.Read
        assertEquals(1, reply.answer.kept.size, "the run survives its neighbours")
        assertEquals(2, reply.answer.unreadable, "the unknown kind and the non-object are both counted")
    }

    @Test
    fun `an unknown cause kind costs the record that carries it, however deep`() {
        val top = run.replace("[ $stateCause ]", """[ { "cause": "throttled" } ]""")
        assertNull(ByRecompositions.parseRecord(obj(top)), "an unknown cause at the top")

        val inDirty = run.replace("[ $stateCause ]", """[ { "cause": "dirty", "causes": [ { "cause": "created" }, { "cause": "throttled" } ] } ]""")
        assertNull(ByRecompositions.parseRecord(obj(inDirty)), "an unknown cause inside `dirty`")

        val inBecause = run.replace(
            "[ $stateCause ]",
            """[ { "cause": "derived", "derived": 1, "declared": null, "declared_name": null, "old": "1", "new": "2",
                   "changed": true, "because": { "cause": "throttled" } } ]""",
        )
        assertNull(ByRecompositions.parseRecord(obj(inBecause)), "an unknown cause behind a `derived`")

        val write = obj("""{ "record": "write", "runtime": 0, "frame": 3, "cause": { "cause": "throttled" } }""")
        assertNull(ByRecompositions.parseRecord(write), "a write whose cause is unknown")
    }

    /**
     * Wrong *types*, not merely missing: Gson's `asLong` on a string throws, and a session must not
     * end because a newer bpd changed a shape. A load-bearing field of the wrong type costs the
     * record; an optional one is absent.
     */
    @Test
    fun `wrongly typed fields read as absent or cost the record`() {
        assertNull(ByRecompositions.parseRecord(obj(run.replace(""""frame": 3""", """"frame": "three""""))))
        assertNull(ByRecompositions.parseRecord(obj(run.replace(""""scope": 5""", """"scope": [5]"""))))
        assertNull(ByRecompositions.parseRecord(obj(run.replace(""""causes": [ $stateCause ]""", """"causes": 7"""))))
        assertNull(ByRecompositions.parseRecord(obj(run.replace(""""record": "run"""", """"record": 1"""))))

        val loose = ByRecompositions.parseRecord(
            obj(
                run.replace(""""elapsed_ns": 12345""", """"elapsed_ns": "fast"""")
                    .replace(""""skipped": [7, 8]""", """"skipped": [7, "eight", 8]""")
                    .replace(""""key": null""", """"key": [1]""")
                    .replace(""""called": {""", """"called": 5, "x": {"""),
            ),
        ) as ByRecord.Run
        assertNull(loose.elapsedNs)
        assertEquals(listOf(7L, 8L), loose.skipped, "a bad element is skipped, not fatal")
        assertNull(loose.key)
        assertNull(loose.called)
    }

    /**
     * `reason` is bpd's `Unmapped`, an internally tagged enum — so an **object** carrying
     * `unmapped` and that tag's own fields, never a string. Spelled here the way
     * `bpd_core::source_map::Unmapped` serialises it, because a shape invented on this side would
     * be a test that agrees with itself and a reason that is silently never shown.
     */
    @Test
    fun `a location keeps its generated place and its reason`() {
        val location = ByRecompositions.parseLocation(
            obj(
                """{ "file": "/tmp/build/a.py", "line": 90, "generated": null,
                     "reason": { "unmapped": "no_source_line", "file": "/tmp/build/a.py", "line": 90,
                                 "source": "/app/a.by" } }""",
            ),
        )
        assertEquals(
            ByTraceLocation("/tmp/build/a.py", 90, null, ByUnmapped.NoSourceLine("/tmp/build/a.py", 90, "/app/a.by")),
            location,
        )
        assertNull(ByRecompositions.parseLocation(obj("""{ "line": 3 }""")), "no file is no location")
        assertNull(ByRecompositions.parseLocation(obj("""{ "file": "/a.by", "line": "3" }""")))
        assertNull(
            ByRecompositions.parseLocation(obj("""{ "file": "/a.by", "line": 3, "generated": { "file": "/a.py" } }"""))?.generated,
            "a generated place missing its line is no generated place",
        )
    }

    /** Every tag `bpd_core::source_map::Unmapped` has, read by the layout of that tag. */
    @Test
    fun `every reason bpd can give is read by its tag`() {
        assertEquals(
            ByUnmapped.NotInTheMap("/a.py"),
            ByRecompositions.parseUnmapped(obj("""{ "unmapped": "not_in_the_map", "file": "/a.py" }""")),
        )
        assertEquals(
            ByUnmapped.PastTheEnd("/a.py", 90, 74),
            ByRecompositions.parseUnmapped(obj("""{ "unmapped": "past_the_end", "file": "/a.py", "line": 90, "covered": 74 }""")),
        )
        assertEquals(
            ByUnmapped.NoGeneratedLine("/a.by", 40, 31),
            ByRecompositions.parseUnmapped(
                obj("""{ "unmapped": "no_generated_line", "file": "/a.by", "requested": 40, "last_mapped": 31 }"""),
            ),
        )
        assertEquals(
            ByUnmapped.NoGeneratedLine("/a.by", 40, null),
            ByRecompositions.parseUnmapped(
                obj("""{ "unmapped": "no_generated_line", "file": "/a.by", "requested": 40, "last_mapped": null }"""),
            ),
            "last_mapped is optional: the transpiler generated nothing for that file at all",
        )
    }

    /**
     * `Unmapped` is `#[non_exhaustive]`, so a newer bpd can send a tag this build has no sentence
     * for — and the location it is about is still exactly the location it was. A reason is the one
     * thing here that degrades to *unknown* rather than to nothing.
     */
    @Test
    fun `a reason this build cannot read never costs the location`() {
        val location = ByRecompositions.parseLocation(
            obj("""{ "file": "/a.by", "line": 3, "reason": { "unmapped": "a_reason_from_2027", "why": "…" } }"""),
        )
        assertEquals("/a.by", location?.file)
        assertEquals(3, location?.line)
        assertEquals(ByUnmapped.Unknown("a_reason_from_2027"), location?.reason)

        assertEquals(
            ByUnmapped.Unknown("past_the_end"),
            ByRecompositions.parseUnmapped(obj("""{ "unmapped": "past_the_end", "file": "/a.py" }""")),
            "a known tag missing its own fields is not the fact the map reported",
        )
        assertEquals(ByUnmapped.Unknown(""), ByRecompositions.parseUnmapped(obj("""{ "file": "/a.py" }""")))
    }

    @Test
    fun `a state cause without a write site is unreadable`() {
        val noSite = stateCause.replace(""""written": { "file": "/app/examples/counter.by", "line": 14,
                       "generated": { "file": "/tmp/build/examples/counter.py", "line": 47 } },""", "")
        assertNull(ByRecompositions.parseCause(obj(noSite)))
    }

    @Test
    fun `an event body is read, with what the stream dropped before it`() {
        val event = ByRecompositions.parseEvent(obj("""{ "record": $run, "dropped_before": 3 }"""))
        assertNotNull(event)
        assertEquals("Counter", (event!!.record as ByRecord.Run).name)
        assertEquals(3L, event.droppedBefore)

        // An older bpd that does not count sends the record alone: nothing was dropped
        assertEquals(0L, ByRecompositions.parseEvent(obj("""{ "record": $run }"""))?.droppedBefore)
        assertEquals(0L, ByRecompositions.parseEvent(obj("""{ "record": $run, "dropped_before": "many" }"""))?.droppedBefore)
        assertEquals(0L, ByRecompositions.parseEvent(obj("""{ "record": $run, "dropped_before": -4 }"""))?.droppedBefore)

        // What was lost before an unreadable record is still lost
        assertEquals(ByEvent(null, 2), ByRecompositions.parseEvent(obj("""{ "record": { "record": "commit", "runtime": 0, "frame": 1 }, "dropped_before": 2 }""")))

        assertNull(ByRecompositions.parseEvent(null))
        assertNull(ByRecompositions.parseEvent(obj("{}")))
        assertNull(ByRecompositions.parseEvent(obj("""{ "record": 3 }""")))
        assertNull(ByRecompositions.parseEvent(obj("""{ "record": 3, "dropped_before": 0 }""")))
    }

    /** The names are the wire, and `bpd/understands` names the event back to switch the prose off. */
    @Test
    fun `the event name and format are the ones bpd uses`() {
        assertEquals("bpd/recomposition", ByRecompositions.EVENT)
        assertEquals(1, ByRecompositions.FORMAT)
    }
}
