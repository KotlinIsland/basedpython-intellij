package dev.basedpython.pycharm.debug

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Total accessors over what bpd sends: what *kind* of thing is under a name, if anything is.
 *
 * Every reader of a bpd body — [ByMoved], the data-flow facts, a code replacement, the compose
 * runtime's trace — reads field by field rather than through a mapped class, because the shapes are
 * bpd's own serialised whole and a class here would be a second copy of a vocabulary that has to
 * agree. What they share is this: a field that is missing or of an unexpected kind is *absent*, never
 * an exception. An event from a newer bpd should cost the feature that reads it, not the session.
 *
 * The kind is checked, not only the presence. kotlinx.serialization's `intOrNull` reads the string
 * `"5"` as 5 and `JsonNull` is a [JsonPrimitive], so a number is a primitive that is not a string
 * and parses as one, and a string is a primitive that says it is one.
 */
internal fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

internal fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray

internal fun JsonObject.string(name: String): String? = get(name)?.stringOrNull()

internal fun JsonObject.int(name: String): Int? = get(name)?.intOrNull()

internal fun JsonObject.long(name: String): Long? = get(name)?.longOrNull()

internal fun JsonObject.bool(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

internal fun JsonObject.strings(name: String): List<String> =
    array(name)?.mapNotNull { it.stringOrNull() }.orEmpty()

internal fun JsonObject.ints(name: String): List<Int> =
    array(name)?.mapNotNull { it.intOrNull() }.orEmpty()

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.intOrNull(): Int? = number()?.intOrNull

internal fun JsonElement.longOrNull(): Long? = number()?.longOrNull

/** A primitive that is a JSON number: not a string, not `null`, not a boolean. */
internal fun JsonElement.number(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { !it.isString && (it.longOrNull != null || it.doubleOrNull != null) }
