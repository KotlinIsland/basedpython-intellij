package dev.basedpython.pycharm.debug.dfa

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one place two independently-built vocabularies have to agree.
 *
 * `bpd` proves facts and `by` narrows with types, and neither knows about the other. If they
 * disagree about a field name or a tag, nothing errors — the feature just quietly draws nothing,
 * which is the failure mode worth a test rather than a look.
 *
 * The JSON here is written the way `bpd` serialises it (`crates/bpd_core/src/fact.rs`, whose serde
 * is `snake_case` with an internal `observed` / `stability` tag), not the way this code would find
 * convenient.
 */
class ByDataFlowFactsTest {

    // `mode` is an object with its own tag, not a string — that is how a real bpd sends it (see
    // the capture in `ByFactsWireTest`). Nothing here reads it, which is exactly why it was worth
    // correcting: an envelope nobody checks is one a reader would otherwise take as documentation
    private fun facts(vararg proved: String) =
        JsonParser.parseString(
            """{"proved":[${proved.joinToString(",")}],"silent":[],"mode":{"mode":"non_stop"}}""",
        ).asJsonObject

    private fun permanent(name: String, observed: String) = """
        {"name":"$name","scope":"local","observed":$observed,"stability":{"stability":"permanent"}}
    """.trimIndent()

    private fun until(name: String, observed: String, mutation: String) = """
        {"name":"$name","scope":"local","observed":$observed,
         "stability":{"stability":"until","mutation":"$mutation"}}
    """.trimIndent()

    @Test
    fun `a permanent literal crosses the vocabularies intact`() {
        val observations = ByDataFlowFacts.observationsOf(
            facts(permanent("limit", """{"observed":"is_int","text":"5"}""")),
        )
        assertEquals(1, observations.size)
        assertEquals("limit", observations[0].name)
        assertEquals("isInt", observations[0].observed)
        assertEquals("5", observations[0].text)
    }

    @Test
    fun `a reading that lasts only until the contents change is not sent`() {
        // this is the whole reason `bpd` reports a stability at all: a length is true now and
        // false after the next `append`, and the code being analysed has not run yet
        val observations = ByDataFlowFacts.observationsOf(
            facts(until("items", """{"observed":"has_length","length":3}""", "contents")),
        )
        assertTrue(observations.isEmpty(), "got $observations")
    }

    @Test
    fun `a class survives a mutation the checker already assumes away`() {
        // `__class__` is assignable on a heap type, so `bpd` reports `until class` — but a type
        // checker already assumes nobody does that, and being stricter than the checker this feeds
        // would be incoherent rather than safe
        val observations = ByDataFlowFacts.observationsOf(
            facts(
                until(
                    "user",
                    """{"observed":"is_exactly","class":{"module":"myapp","qualname":"User"}}""",
                    "class",
                ),
            ),
        )
        assertEquals(1, observations.size)
        assertEquals("isExactly", observations[0].observed)
        assertEquals("myapp", observations[0].module)
        assertEquals("User", observations[0].qualname)
    }

    @Test
    fun `a stability this build does not recognise is treated as no stability at all`() {
        val observations = ByDataFlowFacts.observationsOf(
            facts(until("x", """{"observed":"is_int","text":"1"}""", "something_new")),
        )
        assertTrue(
            observations.isEmpty(),
            "an unknown shelf life is not a long one, and got $observations",
        )
    }

    @Test
    fun `the most specific reading about a name is the one sent`() {
        // an `int` proves both its class and its value, and `Literal[5]` decides `x > 3` where
        // `int` does not. sending both would make the server choose with less to choose from
        val observations = ByDataFlowFacts.observationsOf(
            facts(
                permanent("n", """{"observed":"is_exactly","class":{"module":"builtins","qualname":"int"}}"""),
                permanent("n", """{"observed":"is_int","text":"5"}"""),
            ),
        )
        assertEquals(1, observations.size)
        assertEquals("isInt", observations[0].observed)
    }

    @Test
    fun `an enum member carries the class the source can resolve`() {
        val observations = ByDataFlowFacts.observationsOf(
            facts(
                until(
                    "colour",
                    """{"observed":"is_enum_member","class":{"module":"app","qualname":"Colour"},"member":"RED"}""",
                    "attributes",
                ),
            ),
        )
        assertEquals(1, observations.size)
        assertEquals("isEnumMember", observations[0].observed)
        assertEquals("Colour", observations[0].qualname)
        assertEquals("RED", observations[0].member)
    }

    @Test
    fun `a fact bpd can prove and by cannot use is dropped without complaint`() {
        val observations = ByDataFlowFacts.observationsOf(
            facts(permanent("ratio", """{"observed":"is_float","text":"1.5"}""")),
        )
        assertTrue(
            observations.isEmpty(),
            "a float is proved and unusable, which is not an error: $observations",
        )
    }

    @Test
    fun `no reply at all is no observations rather than a failure`() {
        assertTrue(ByDataFlowFacts.observationsOf(null).isEmpty())
    }
}

/** What the client asks a debugger about, and what it leaves out. */
class ByDataFlowNamesTest {

    @Test
    fun `only names below the stop line are asked about`() {
        val text = "above = 1\nstopped = 2\nbelow = 3\n"
        val names = ByDataFlowNames.below(text, text.indexOf("below"))
        assertTrue(names.contains("below"), "got $names")
        assertTrue(!names.contains("above"), "a name above the stop line already ran: $names")
    }

    @Test
    fun `a dotted path is asked about whole`() {
        val names = ByDataFlowNames.below("if self.limit > 3: pass", 0)
        assertTrue(names.contains("self.limit"), "got $names")
    }

    @Test
    fun `keywords are not names a debugger has anything to say about`() {
        val names = ByDataFlowNames.below("if limit is None: pass", 0)
        assertEquals(listOf("limit"), names.filter { it in setOf("limit", "if", "is", "None") })
    }

    @Test
    fun `the number of names one stop asks about is bounded`() {
        val text = (1..500).joinToString(" ") { "name$it" }
        assertEquals(ByDataFlowNames.MAX_NAMES, ByDataFlowNames.below(text, 0).size)
    }
}
