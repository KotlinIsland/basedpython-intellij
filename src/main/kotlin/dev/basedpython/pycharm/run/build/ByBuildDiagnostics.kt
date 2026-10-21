package dev.basedpython.pycharm.run.build

/**
 * One diagnostic of a `by build`, as the build printed it.
 *
 * [line] and [column] are 1-based, as `by` counts them; [column] is absent when the diagnostic
 * spans lines, and [file] when it is about no file at all.
 */
internal data class ByBuildDiagnostic(
    val severity: Severity,
    val rule: String,
    val file: String?,
    val line: Int?,
    val column: Int?,
    /** The diagnostic's first line, without its place or its rule. */
    val title: String,
    /** Everything `by` said, the lines naming its other places included. */
    val message: String,
) {
    enum class Severity { ERROR, WARNING, INFO }
}

/**
 * Reads the diagnostics out of a `by build`'s output.
 *
 * ## why a format, and why this one
 *
 * `by build` prints what it wrote and what it found on one stream. Its default rendering of a
 * diagnostic is for a person — a header, an arrow to the place, the source with carets under it —
 * and taking that apart would be guessing at prose. So the build is asked for [FORMAT] instead,
 * through `TY_OUTPUT_FORMAT`, which `by build --output-format` reads as `by check`'s does: GitHub's
 * workflow-command format, one diagnostic per line, with the file, the place and the severity as
 * named fields and the line starting `::`, which nothing else the build prints does.
 *
 * Through the environment rather than the flag because a `by` from before the flag rejects the
 * flag and fails the build, and ignores the variable: it prints its diagnostics for a person,
 * every line of which [parse] passes over, and the build window shows them as text.
 *
 * The format writes its fields unescaped, so a field is found by the one after it rather than by
 * splitting on commas: a path with a comma in it is still one path.
 */
internal object ByBuildDiagnostics {

    /** The `TY_OUTPUT_FORMAT` that has `by build` print one line per diagnostic. */
    const val FORMAT = "github"

    /** The environment variable `by` reads its diagnostic format from. */
    const val FORMAT_VARIABLE = "TY_OUTPUT_FORMAT"

    private val COMMAND = Regex(
        "^::(error|warning|notice) title=(.*?)" +
            "(?:,file=(.*?),line=(\\d+)(?:,col=(\\d+))?,endLine=\\d+(?:,endColumn=\\d+)?)?::(.*)$",
    )

    /** The rule, in the parentheses that end the title: `ty (invalid-assignment)`. */
    private val RULE = Regex("\\(([^()]*)\\)$")

    /** The diagnostic, or null for any other line the build printed. */
    fun parse(line: String): ByBuildDiagnostic? {
        val match = COMMAND.matchEntire(line.trimEnd('\n', '\r')) ?: return null
        val (kind, title, file, lineText, columnText, rest) = match.destructured
        val rule = RULE.find(title)?.groupValues?.get(1) ?: title
        val number = lineText.toIntOrNull()
        val message = stripPlace(decode(rest), number)
        val first = message.lineSequence().first()
        return ByBuildDiagnostic(
            severity = when (kind) {
                "error" -> ByBuildDiagnostic.Severity.ERROR
                "warning" -> ByBuildDiagnostic.Severity.WARNING
                else -> ByBuildDiagnostic.Severity.INFO
            },
            rule = rule,
            file = file.ifEmpty { null },
            line = number,
            column = columnText.toIntOrNull(),
            title = first.removePrefix("$rule: ").removePrefix("$rule:").trim().ifEmpty { first },
            message = message,
        )
    }

    /**
     * The message without the `path:line:column: ` it starts with when it has a place, which the
     * fields already say.
     */
    private fun stripPlace(message: String, line: Int?): String {
        if (line == null) return message
        val place = Regex("^.*?:$line:\\d+: ").find(message) ?: return message
        return message.substring(place.range.last + 1)
    }

    /**
     * Puts back the line breaks between a diagnostic's lines, which the format writes as `%0A` to
     * keep the diagnostic on one line. Nothing else is escaped by `by`, so nothing else is undone.
     */
    private fun decode(text: String): String = text.replace("%0A", "\n")
}
