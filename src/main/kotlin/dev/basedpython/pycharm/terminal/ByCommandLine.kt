package dev.basedpython.pycharm.terminal

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * `by`'s command line as `by/commandLine` answers it: every command and argument `--help` shows,
 * described from the parser `by` parses its own arguments with (`by`'s
 * `docs/basedpython/features/editor.md`, *completing `by` in a terminal*).
 *
 * Read field by field rather than through a `@Serializable` class: the answer is `by`'s, and a field
 * or a value kind a newer `by` adds costs what reads it — an unknown kind completes nothing — rather
 * than the whole answer.
 */
internal data class ByCommand(
    val name: String,
    val aliases: List<String>,
    val help: String?,
    val options: List<ByOption>,
    val positionals: List<ByPositional>,
    val subcommands: List<ByCommand>,
) {
    companion object {
        /** The command [json] describes, or null when it is not one: no `name`. */
        fun of(json: JsonElement?): ByCommand? {
            val obj = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return ByCommand(
                name = obj.string("name") ?: return null,
                aliases = obj.strings("aliases"),
                help = obj.string("help"),
                options = obj.objects("options").mapNotNull(ByOption::of),
                positionals = obj.objects("positionals").map(ByPositional::of),
                subcommands = obj.objects("subcommands").mapNotNull(::of),
            )
        }
    }
}

/** An option: `--long`, `-s` and any aliases, a flag when [value] is null. */
internal data class ByOption(
    val long: String?,
    val short: String?,
    val aliases: List<String>,
    val help: String?,
    val value: ByValue?,
    val repeatable: Boolean,
) {
    /** The names it is typed by: `--long`, `-s`, and each alias as a long name. */
    val names: List<String>
        get() = listOfNotNull(long?.let { "--$it" }, short?.let { "-$it" }) + aliases.map { "--$it" }

    companion object {
        fun of(json: JsonObject): ByOption? {
            val option = ByOption(
                long = json.string("long"),
                short = json.string("short"),
                aliases = json.strings("aliases"),
                help = json.string("help"),
                value = json.get("value")?.takeIf { it.isJsonObject }?.asJsonObject?.let(ByValue::of),
                repeatable = json.boolean("repeatable"),
            )
            return option.takeIf { it.names.isNotEmpty() }
        }
    }
}

/**
 * A positional argument. [trailing] takes the rest of the line, options included — after `by run`'s
 * module, the arguments of the program it runs, which `by` does not interpret and nothing completes.
 */
internal data class ByPositional(
    val help: String?,
    val value: ByValue,
    val required: Boolean,
    val repeatable: Boolean,
    val trailing: Boolean,
) {
    companion object {
        fun of(json: JsonObject) = ByPositional(
            help = json.string("help"),
            value = json.get("value")?.takeIf { it.isJsonObject }?.asJsonObject?.let(ByValue::of) ?: ByValue.Text(null),
            required = json.boolean("required"),
            repeatable = json.boolean("repeatable"),
            trailing = json.boolean("trailing"),
        )
    }
}

/** What a value is, which says how it is completed. */
internal sealed interface ByValue {
    /** The placeholder `--help` shows for it: `MODULE`, `PATH`. */
    val name: String?

    /** A module of the project by its dotted name: `by/runModules`' answer. */
    data class Module(override val name: String?) : ByValue

    /** A rule by its name: `by/rules`' answer, or `all`. */
    data class Rule(override val name: String?) : ByValue

    /** A path, completed from the file system; [hint] is `any`, `file`, `directory` or `executable`. */
    data class Path(override val name: String?, val hint: String?) : ByValue

    /** One of [choices]. */
    data class Choice(override val name: String?, val choices: List<Named>) : ByValue

    /** Anything, which nothing completes — and a kind this plugin does not know, which it does not guess at. */
    data class Text(override val name: String?) : ByValue

    data class Named(val name: String, val help: String?)

    companion object {
        fun of(json: JsonObject): ByValue {
            val name = json.string("name")
            return when (json.string("kind")) {
                "module" -> Module(name)
                "rule" -> Rule(name)
                "path" -> Path(name, json.string("hint"))
                "choice" -> Choice(
                    name,
                    json.objects("choices").mapNotNull { choice -> choice.string("name")?.let { Named(it, choice.string("help")) } },
                )
                else -> Text(name)
            }
        }
    }
}

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.boolean(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false

private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.strings(key: String): List<String> =
    array(key)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }.orEmpty()

private fun JsonObject.objects(key: String): List<JsonObject> =
    array(key)?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
