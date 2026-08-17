package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.tasks.ByToml
import dev.basedpython.pycharm.tasks.ByTomlSection

/**
 * The two edits to a `pyproject.toml` that uv has no command for.
 *
 * Everything else this feature does goes through uv — `uv init` creates a module and lists it,
 * `uv add`/`uv remove` wire the dependencies between modules and write `[tool.uv.sources]` — and
 * that is the rule rather than a coincidence: a tool that owns a file is the thing that should be
 * rewriting it. What is left over is what uv genuinely does not offer:
 *
 * - **taking a `members` entry back out**, because uv adds one and never removes one, so a module
 *   deleted from disk stays listed in the root manifest,
 * - **setting a project's own metadata** — version, description, `requires-python` — which uv reads
 *   and never writes.
 *
 * ### Why it edits text rather than re-emitting the document
 *
 * A `pyproject.toml` is a file a person wrote. Round-tripping it through a parser and printing it
 * back reformats every table, reorders nothing predictably and drops comments — for a change of one
 * value. So the file's text is kept and one entry's lines are replaced, which is also what makes
 * every case here a string in a test.
 *
 * The reader's limits ([ByToml]) are this writer's limits too: dotted keys and arrays of tables are
 * not understood, and a manifest written with either is left alone rather than mangled — see
 * [setString], which returns the text unchanged when it cannot find what it was asked to change.
 */
internal object TomlEdits {

    /**
     * [text] with [key] in the table at [path] set to [value], or with the key removed when [value]
     * is null or blank.
     *
     * Removing on blank rather than writing `key = ""` is the deliberate choice: an empty
     * description is the absence of one, and a manifest carrying `description = ""` says something
     * different to every tool that reads it than a manifest carrying nothing.
     *
     * Returns [text] unchanged when the table does not exist and there is nothing to add — there is
     * no sensible place to invent a `[project]` table in a file that has none, and this is asked
     * only about manifests that already have one.
     */
    fun setString(text: String, path: List<String>, key: String, value: String?): String {
        val wanted = value?.trim().orEmpty()
        val sections = ByToml.parse(text)
        val section = sections.firstOrNull { it.path == path } ?: return text
        val lines = text.lines().toMutableList()
        val existing = section.entries.firstOrNull { it.key == key }

        if (existing == null) {
            // Nothing to remove, and nothing that adding an empty one would say.
            if (wanted.isEmpty()) return text
            lines.add(insertionPoint(sections, section, lines), "$key = ${quote(wanted)}")
            return join(text, lines)
        }

        val span = span(sections, section, existing.line, lines)
        if (wanted.isEmpty()) {
            lines.subList(span.first, span.last + 1).clear()
        } else {
            lines[span.first] = "$key = ${quote(wanted)}"
            if (span.last > span.first) lines.subList(span.first + 1, span.last + 1).clear()
        }
        return join(text, lines)
    }

    /**
     * [text] with [item] taken out of the array at [path] / [key], or unchanged when it is not there.
     *
     * The array's shape is preserved rather than normalised: uv writes `members` one entry per line
     * and a person may well have written it on one, and a removal that also reflows the array is a
     * diff about something other than what was asked for.
     */
    fun removeArrayItem(text: String, path: List<String>, key: String, item: String): String {
        val sections = ByToml.parse(text)
        val section = sections.firstOrNull { it.path == path } ?: return text
        val entry = section.entries.firstOrNull { it.key == key } ?: return text
        val items = entry.strings()
        val kept = items.filter { UvWorkspace.normalizePattern(it) != UvWorkspace.normalizePattern(item) }
        if (kept.size == items.size) return text

        val lines = text.lines().toMutableList()
        val span = span(sections, section, entry.line, lines)
        val multiline = span.last > span.first
        val rendered = render(key, kept, multiline)
        lines.subList(span.first, span.last + 1).clear()
        lines.addAll(span.first, rendered)
        return join(text, lines)
    }

    /**
     * [text] with [item] added to the array at [path] / [key], creating the array when it has none.
     *
     * Unchanged when the item is already there, so the operation is idempotent — a module excluded
     * twice would otherwise accumulate entries. The table itself is never invented: this is asked
     * only about `[tool.uv.workspace]`, which exists by definition on a project that has members.
     */
    fun addArrayItem(text: String, path: List<String>, key: String, item: String): String {
        val sections = ByToml.parse(text)
        val section = sections.firstOrNull { it.path == path } ?: return text
        val entry = section.entries.firstOrNull { it.key == key }
        val wanted = UvWorkspace.normalizePattern(item)
        if (wanted.isEmpty()) return text

        val lines = text.lines().toMutableList()
        if (entry == null) {
            lines.add(insertionPoint(sections, section, lines), "$key = [${quote(wanted)}]")
            return join(text, lines)
        }

        val items = entry.strings()
        if (items.any { UvWorkspace.normalizePattern(it) == wanted }) return text
        val span = span(sections, section, entry.line, lines)
        val rendered = render(key, items + wanted, multiline = span.last > span.first)
        lines.subList(span.first, span.last + 1).clear()
        lines.addAll(span.first, rendered)
        return join(text, lines)
    }

    // ---- plumbing ----------------------------------------------------------

    /**
     * The lines the entry starting at [start] occupies.
     *
     * Bounded by whatever comes next — the following entry, the following table header, or the end
     * of the file — and then trimmed back over blank and comment lines, so that a comment written
     * *before* the next key stays with the next key rather than being deleted along with this
     * entry's value. A value spanning several lines (an array, a triple-quoted string) is covered by
     * the same rule, because nothing else in the table can start before it has closed.
     */
    private fun span(
        sections: List<ByTomlSection>,
        section: ByTomlSection,
        start: Int,
        lines: List<String>,
    ): IntRange {
        val nextEntry = section.entries.map { it.line }.filter { it > start }.minOrNull()
        val nextSection = sections.map { it.line }.filter { it > start }.minOrNull()
        val bound = listOfNotNull(nextEntry, nextSection, lines.size).min()
        var last = bound - 1
        while (last > start && lines[last].isTrailing()) last--
        return start..last.coerceAtLeast(start)
    }

    /** True for a line that belongs to whatever comes next rather than to the entry above it. */
    private fun String.isTrailing(): Boolean {
        val trimmed = trim()
        return trimmed.isEmpty() || trimmed.startsWith('#')
    }

    /** Where a new key goes: after the table's last entry, or directly under its header. */
    private fun insertionPoint(sections: List<ByTomlSection>, section: ByTomlSection, lines: List<String>): Int {
        val lastEntry = section.entries.maxByOrNull { it.line }
        val after = if (lastEntry != null) {
            span(sections, section, lastEntry.line, lines).last + 1
        } else {
            section.line + 1
        }
        return after.coerceIn(0, lines.size)
    }

    /** The array as source lines, in the shape it already had. */
    private fun render(key: String, items: List<String>, multiline: Boolean): List<String> = when {
        items.isEmpty() -> listOf("$key = []")
        multiline -> listOf("$key = [") + items.map { "    ${quote(it)}," } + listOf("]")
        else -> listOf("$key = [${items.joinToString(", ") { quote(it) }}]")
    }

    /** [value] as a TOML basic string, with the two characters that cannot appear raw escaped. */
    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * The lines back into a file, keeping the line ending the file already used.
     *
     * A manifest written on Windows is CRLF throughout, and rewriting one value in it must not turn
     * the whole file into a one-line diff.
     */
    private fun join(original: String, lines: List<String>): String =
        lines.joinToString(if (original.contains("\r\n")) "\r\n" else "\n")
}
