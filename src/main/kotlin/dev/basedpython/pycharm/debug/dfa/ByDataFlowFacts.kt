package dev.basedpython.pycharm.debug.dfa

import com.google.gson.JsonObject

/**
 * Turning what `bpd` proved about a frame into what `by` can narrow with.
 *
 * The two vocabularies are close but not the same, and the difference is the point: `bpd` reports
 * a reading **and how long it stays true**, and this is where that second half is spent. A fact
 * whose shelf life does not reach the code being analysed is dropped here rather than sent and
 * relied on.
 *
 * Pure, so it is testable without an IDE, a debugger or a language server — which matters, because
 * this is the one place two independently-built vocabularies have to agree, and a mismatch here
 * looks like "the feature draws nothing" rather than like an error.
 *
 * ## Why only `bpd`
 *
 * The stability judgement can only be made by something holding the object: whether a type is a
 * heap type, whether instances keep a dictionary, whether a length can change. A DAP `variables`
 * reply carries none of it. So a session against an adapter without `bpd/facts` gets no data flow
 * rather than data flow built on a guess — see [ByDataFlowSession].
 */
object ByDataFlowFacts {

    /**
     * The mutations that do not stop a fact being worth sending.
     *
     * A type checker already assumes `__class__` is not reassigned under it and that an attribute
     * read twice gives the same answer; an analysis that were stricter than the checker it feeds
     * would be incoherent rather than safe.
     *
     * `contents` is deliberately absent. A reading whose truth depends on a container not being
     * appended to is exactly the one that goes stale between the stop line and the branch below it.
     */
    private val TOLERATED_MUTATIONS = setOf("class", "attributes")

    /** The observation kinds `by` understands. Anything else `bpd` proves is not sendable. */
    private const val IS_NONE = "isNone"
    private const val IS_BOOL = "isBool"
    private const val IS_INT = "isInt"
    private const val IS_FLOAT = "isFloat"
    private const val IS_STR = "isStr"
    private const val IS_EXACTLY = "isExactly"
    private const val IS_ENUM_MEMBER = "isEnumMember"

    /**
     * Every fact in a `bpd/facts` reply that is worth sending, in the order it was proved.
     *
     * One name can produce several facts and at most one of them survives: the most specific
     * reading wins, because `x == 5` narrows everything `type(x) is int` does and more. Sending
     * both would make the server pick, and it has less to pick with.
     */
    fun observationsOf(reply: JsonObject?): List<ByObservation> {
        val proved = reply?.getAsJsonArray("proved") ?: return emptyList()

        val best = LinkedHashMap<String, ByObservation>()
        for (element in proved) {
            val fact = element as? JsonObject ?: continue
            if (!isDurable(fact)) continue
            val observation = observationOf(fact) ?: continue
            val existing = best[observation.name]
            if (existing == null || specificity(observation) > specificity(existing)) {
                best[observation.name] = observation
            }
        }
        return best.values.toList()
    }

    /** Whether a fact stays true long enough to say anything about code that has not run. */
    private fun isDurable(fact: JsonObject): Boolean {
        val stability = fact.getAsJsonObject("stability") ?: return false
        return when (stability.get("stability")?.asString) {
            "permanent" -> true
            "until" -> stability.get("mutation")?.asString in TOLERATED_MUTATIONS
            // A stability `bpd` grew that this build does not know is not one to assume is
            // harmless. An unknown shelf life is not a long one
            else -> false
        }
    }

    /**
     * One fact as an observation, or `null` when `by` has no way to use it.
     *
     * A length and a truthiness are the ordinary `null`s: neither is a set of values, so neither is
     * a type, and the server has nothing to narrow with. They are not errors and not worth
     * reporting — the fact was proved, it simply does not translate.
     */
    private fun observationOf(fact: JsonObject): ByObservation? {
        val name = fact.get("name")?.asString ?: return null
        val observed = fact.getAsJsonObject("observed") ?: return null

        return when (observed.get("observed")?.asString) {
            "is_none" -> ByObservation(name, IS_NONE)

            "is_bool" -> ByObservation(
                name,
                IS_BOOL,
                value = observed.get("value")?.asBoolean ?: return null,
            )

            "is_int" -> ByObservation(
                name,
                IS_INT,
                text = observed.get("text")?.asString ?: return null,
            )

            // `float.__repr__`'s own text, forwarded rather than parsed. A json number would lose
            // `inf` and `nan`, which json cannot spell — and deciding what a float can be narrowed
            // to is `by`'s judgement, not this one's: it refuses a literal for `nan`, which is not
            // equal to itself, and for `-0.0`, which is equal to `0.0` while being a distinct
            // literal.
            "is_float" -> ByObservation(
                name,
                IS_FLOAT,
                text = observed.get("text")?.asString ?: return null,
            )

            "is_str" -> ByObservation(
                name,
                IS_STR,
                text = observed.get("text")?.asString ?: return null,
            )

            "is_exactly" -> {
                val cls = observed.getAsJsonObject("class") ?: return null
                ByObservation(
                    name,
                    IS_EXACTLY,
                    module = cls.get("module")?.asString ?: return null,
                    qualname = cls.get("qualname")?.asString ?: return null,
                )
            }

            "is_enum_member" -> {
                val cls = observed.getAsJsonObject("class") ?: return null
                ByObservation(
                    name,
                    IS_ENUM_MEMBER,
                    module = cls.get("module")?.asString ?: return null,
                    qualname = cls.get("qualname")?.asString ?: return null,
                    member = observed.get("member")?.asString ?: return null,
                )
            }

            // `is_bytes`, `has_length`, `is_truthy` — proved, and nothing here can spend them.
            // `by` has no observation for a raw byte string, and the last two are properties of a
            // value rather than sets of them
            else -> null
        }
    }

    /**
     * How much a reading pins down, so the most specific one about a name wins.
     *
     * An exact value beats a class: `Literal[5]` decides `x > 3`, and `int` does not.
     */
    private fun specificity(observation: ByObservation): Int = when (observation.observed) {
        IS_NONE, IS_BOOL, IS_INT, IS_FLOAT, IS_STR -> 2
        IS_ENUM_MEMBER -> 1
        else -> 0
    }
}
