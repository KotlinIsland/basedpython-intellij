package dev.basedpython.pycharm.run.main

import com.intellij.util.execution.ParametersListUtil

/**
 * The command line for a `main`, and the way back from one.
 *
 * Values are held as a parameter-name → text map, in which an absent key means "not given, let the
 * default stand". A [ByCliType.BOOL] value is `"true"` or `"false"`, the two flags argparse
 * registers for it.
 *
 * Everything is written in the `--name value` form even for parameters that would take a positional
 * slot. Both spellings reach `main` — a positional-only parameter is still handed over positionally
 * — and the named one survives a reordered signature, says what it is when read back out of the run
 * configuration, and never trips the "cannot be given without …" rule that a gap in the positional
 * run of arguments would.
 */
internal object ByMainArguments {

    /** The arguments to append after the module, for [values]. */
    fun arguments(main: ByMainFunction, values: Map<String, String>): List<String> = buildList {
        for (parameter in main.exposed) {
            val value = values[parameter.name] ?: continue
            if (parameter.type == ByCliType.BOOL) {
                add(if (value.toBoolean()) parameter.flag else parameter.negativeFlag)
            } else {
                add(parameter.flag)
                add(value)
            }
        }
    }

    /** [arguments] as one shell-quoted string, which is how a run configuration stores them. */
    fun format(main: ByMainFunction, values: Map<String, String>): String =
        ParametersListUtil.join(arguments(main, values))

    /**
     * The values [text] gives each parameter, or null when the form cannot express it.
     *
     * Null is not an error: it is how a hand-written command line — an unknown flag, a `--` escape,
     * something `main`'s signature says nothing about — asks to be edited as text rather than
     * silently reduced to the fields that happen to fit.
     */
    fun parse(main: ByMainFunction, text: String): Map<String, String>? {
        val tokens = ParametersListUtil.parse(text)
        val byFlag = mutableMapOf<String, ByMainParameter>()
        val negative = mutableMapOf<String, ByMainParameter>()
        for (parameter in main.exposed) {
            parameter.flags.forEach { byFlag[it] = parameter }
            if (parameter.type == ByCliType.BOOL) {
                parameter.flags.forEach { negative["--no-${it.removePrefix("--")}"] = parameter }
            }
        }
        // The parameters a bare value fills, in the order argparse would fill them.
        val slots = main.exposed
            .filter { it.kind != ByParameterKind.KEYWORD && it.type != ByCliType.BOOL }
            .toMutableList()

        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            index++
            if (token.startsWith("--")) {
                val split = token.indexOf('=')
                val flag = if (split < 0) token else token.substring(0, split)
                val inline = if (split < 0) null else token.substring(split + 1)
                val parameter = byFlag[flag] ?: negative[flag] ?: return null
                if (values.containsKey(parameter.name)) return null
                if (parameter.type == ByCliType.BOOL) {
                    // `--verbose` and `--no-verbose` are `store_true`/`store_false`: no value.
                    if (inline != null) return null
                    values[parameter.name] = (flag in parameter.flags).toString()
                } else {
                    val value = inline ?: tokens.getOrNull(index)?.also { index++ } ?: return null
                    values[parameter.name] = value
                }
                slots.remove(parameter)
                continue
            }
            // A lone `-x` is not a spelling anything here has; a negative number is a value.
            if (token.startsWith("-") && token.toDoubleOrNull() == null) return null
            val parameter = slots.removeFirstOrNull() ?: return null
            if (values.containsKey(parameter.name)) return null
            values[parameter.name] = token
        }
        return values
    }

    /**
     * The required parameters [text] leaves unfilled — the arguments a run started now would die
     * on, with `error: the following arguments are required: …`.
     *
     * A command line the form cannot read is taken at its word and reported as complete: it was
     * written by hand, and guessing that it is wrong is worse than letting it run.
     */
    fun missing(main: ByMainFunction, text: String): List<ByMainParameter> {
        val values = parse(main, text) ?: return emptyList()
        return main.required.filter { it.name !in values }
    }
}
