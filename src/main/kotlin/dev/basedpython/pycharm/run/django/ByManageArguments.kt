package dev.basedpython.pycharm.run.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoArgument

/**
 * A command line read against its command's arguments, as `by/djangoCommand` lists them, and written
 * back: what keeps the Commands view's form and its command line saying the same.
 *
 * Reads as `argparse` does: options anywhere, spelled short, long, by an unambiguous beginning, with
 * their value attached or several flags in one word; positionals matched from the left, each leaving
 * what those after it require. What it cannot place — an option `by` does not list with the words after
 * it, everything from `--` — is kept as it is, for `manage.py` to accept or refuse.
 *
 * An argument is itself, not its name: `showmigrations --list` and `--plan` have one name between them.
 */
internal class ByManageArguments(private val arguments: List<ByDjangoArgument>) {

    /** A stretch of the line: an option's, with the values it was given, or words that are nobody's. */
    private class Piece(val argument: ByDjangoArgument?, val values: List<String>, val words: List<String>)

    private class Line(val pieces: List<Piece>, val positional: List<String>, val tail: List<String>)

    private val positionals = arguments.filter { it.flags.isEmpty() }
    private val byFlag = arguments.flatMap { argument -> argument.flags.map { it to argument } }.toMap()

    /** Each argument's values in [words], which follow the command's name — one `""` per time a flag is there. */
    fun read(words: List<String>): Map<ByDjangoArgument, List<String>> {
        val line = line(words)
        val read = linkedMapOf<ByDjangoArgument, List<String>>()
        for ((argument, values) in placed(line.positional)) if (values.isNotEmpty()) read[argument] = values
        for (piece in line.pieces) {
            val argument = piece.argument ?: continue
            if (piece.values.isEmpty()) continue
            read[argument] = if (accumulates(argument)) read[argument].orEmpty() + piece.values else piece.values
        }
        return read
    }

    /** [words] with the option [argument] given [values], or not given when they are empty. */
    fun write(words: List<String>, argument: ByDjangoArgument, values: List<String>): List<String> {
        val line = line(words)
        val spelled = spell(argument, values)
        val options = mutableListOf<String>()
        var written = false
        for (piece in line.pieces) {
            if (piece.argument != argument) {
                options += piece.words
            } else if (!written) {
                options += spelled
                written = true
            }
        }
        if (!written) options += spelled
        return line.positional + options + line.tail
    }

    /** A line and the positionals it could not be given, for want of one before them. */
    class Written(val words: List<String>, val pending: Set<ByDjangoArgument>)

    /**
     * [words] with every positional as [values] has it. `argparse` knows them by their place alone, so one
     * whose place another would take without it is left out of the line until that one is given.
     */
    fun writePositionals(words: List<String>, values: Map<ByDjangoArgument, List<String>>): Written {
        val line = line(words)
        var given = positionals.filter { values[it].orEmpty().isNotEmpty() }
        while (given.isNotEmpty()) {
            val placed = placed(given.flatMap { values.getValue(it) })
            if (given.all { placed[it] == values[it] }) break
            given = given.dropLast(1)
        }
        // words past the last positional are nobody's, and manage.py's to refuse
        val extra = line.positional.drop(placed(line.positional).values.sumOf { it.size })
        val written = given.flatMap { values.getValue(it) } + extra + line.pieces.flatMap { it.words } + line.tail
        return Written(written, positionals.filter { values[it].orEmpty().isNotEmpty() && it !in given }.toSet())
    }

    private fun spell(argument: ByDjangoArgument, values: List<String>): List<String> {
        if (values.isEmpty()) return emptyList()
        val flag = argument.flags.firstOrNull { it.startsWith("--") } ?: argument.flags.firstOrNull() ?: return values
        return when (argument.kind) {
            ByDjangoArgument.FLAG -> listOf(flag)
            ByDjangoArgument.COUNT -> values.map { flag }
            ByDjangoArgument.APPEND -> values.flatMap { spelled(flag, it) }
            ByDjangoArgument.LIST -> listOf(flag) + values
            else -> spelled(flag, values.last())
        }
    }

    /** A value that reads as an option can only be given attached to its own. */
    private fun spelled(flag: String, value: String): List<String> =
        if (value.startsWith("-") && flag.startsWith("--")) listOf("$flag=$value") else listOf(flag, value)

    private fun line(words: List<String>): Line {
        val end = words.indexOf("--").let { if (it < 0) words.size else it }
        val pieces = mutableListOf<Piece>()
        val positional = mutableListOf<String>()
        var at = 0

        fun valuesAfter(most: Int): List<String> = words.subList(at + 1, end).take(most).takeWhile { !isOption(it) }

        while (at < end) {
            val word = words[at]
            if (!isOption(word)) {
                positional += word
                at++
                continue
            }
            val flags = flags(word)
            if (flags != null) {
                // `-rd` is `-r -d`
                flags.forEach { pieces += Piece(it.second, listOf(""), listOf(it.first)) }
                at++
                continue
            }
            val (option, attached) = option(word)
            when {
                // whose the words after it are is not known
                option == null -> {
                    val after = valuesAfter(Int.MAX_VALUE)
                    pieces += Piece(null, emptyList(), listOf(word) + after)
                    at += after.size
                }
                attached != null -> pieces += Piece(option, listOf(attached), listOf(word))
                else -> {
                    // an option still waiting for its value gives nothing yet
                    val after = valuesAfter(if (option.kind == ByDjangoArgument.LIST) Int.MAX_VALUE else 1)
                    pieces += Piece(option, after, listOf(word) + after)
                    at += after.size
                }
            }
            at++
        }
        return Line(pieces, positional, words.drop(end))
    }

    /** The flags [word] is made of, each as it would be spelled alone, or null when it is anything else. */
    private fun flags(word: String): List<Pair<String, ByDjangoArgument>>? {
        option(word).let { (option, attached) ->
            if (option != null && attached == null && option.takesNothing) return listOf(word to option)
        }
        if (word.length < 3 || word.startsWith("--")) return null
        return word.drop(1).map { char -> "-$char" to (byFlag["-$char"]?.takeIf { it.takesNothing } ?: return null) }
    }

    /** The option [word] names, and the value it has attached. */
    private fun option(word: String): Pair<ByDjangoArgument?, String?> {
        byFlag[word]?.let { return it to null }
        if (!word.startsWith("--")) {
            val short = byFlag[word.take(2)]?.takeIf { !it.takesNothing } ?: return null to null
            return short to word.drop(2).removePrefix("=")
        }
        val name = word.substringBefore('=')
        val value = if ('=' in word) word.substringAfter('=') else null
        val named = byFlag[name] ?: byFlag.filterKeys { it.startsWith(name) }.values.distinct().singleOrNull()
        return if (named != null && named.takesNothing && value != null) null to null else named to value
    }

    /** The positionals [words] give, matched as `argparse` does, whose matching is a regular expression's. */
    private fun placed(words: List<String>): Map<ByDjangoArgument, List<String>> {
        val placed = linkedMapOf<ByDjangoArgument, List<String>>()
        var left = words
        for ((index, positional) in positionals.withIndex()) {
            val needed = positionals.drop(index + 1).count { it.required }
            val spare = (left.size - needed).coerceAtLeast(0)
            val most = when {
                positional.kind == ByDjangoArgument.LIST -> spare
                else -> 1
            }
            // a required one takes its word though those after it then want for theirs: the line is half-typed
            val takes = if (positional.required) most.coerceIn(minOf(1, left.size), left.size) else minOf(most, spare)
            placed[positional] = left.take(takes)
            left = left.drop(takes)
        }
        return placed
    }

    private fun accumulates(argument: ByDjangoArgument) = argument.kind == ByDjangoArgument.APPEND || argument.kind == ByDjangoArgument.COUNT

    private fun isOption(word: String): Boolean = word.length > 1 && word[0] == '-' && word.toDoubleOrNull() == null

    private val ByDjangoArgument.takesNothing: Boolean get() = kind == ByDjangoArgument.FLAG || kind == ByDjangoArgument.COUNT
}
