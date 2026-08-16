package dev.basedpython.pycharm.tasks

/** One `key = value` of a TOML table, with the value kept as written. */
internal data class ByTomlEntry(val key: String, val raw: String, val line: Int) {

    /** The value as a string, when it is one; a number, a boolean or an array is not. */
    fun string(): String? {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return null
        val quote = trimmed.first()
        if (quote != '"' && quote != '\'') return null
        return ByToml.unquote(trimmed)
    }

    /** The value as a list of strings — one string counts as a list of one, an array as its items. */
    fun strings(): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return listOfNotNull(string())
        return ByToml.splitFlow(trimmed).mapNotNull { item ->
            item.trim().takeIf { it.length >= 2 && (it.first() == '"' || it.first() == '\'') }?.let(ByToml::unquote)
        }
    }

    /** The value of [key] inside an inline table, when this entry is one. */
    fun inline(key: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        return ByToml.splitFlow(trimmed)
            .firstNotNullOfOrNull { item ->
                val separator = item.indexOf('=')
                if (separator < 0) return@firstNotNullOfOrNull null
                val name = ByToml.unquote(item.substring(0, separator).trim())
                if (name != key) null else ByToml.unquote(item.substring(separator + 1).trim())
            }
    }
}

/**
 * One `[table]` and the keys directly under it, in the order they were written.
 *
 * @param path the dotted header split out — `[tool.pyprojectx.aliases]` is `["tool", "pyprojectx",
 *   "aliases"]`. The file's top level, before any header, is the empty path.
 */
internal data class ByTomlSection(val path: List<String>, val line: Int, val entries: List<ByTomlEntry>)

/**
 * The slice of TOML that `[tool.pyprojectx]` is written in.
 *
 * Same trade as [ByYaml], for the same reason: this reads one table out of a `pyproject.toml` to
 * list its aliases, and it does that as a pure function of the file's text, which is what makes it
 * testable against real configuration rather than against a fixture project. The TOML plugin is a
 * dependency of this plugin already and would parse the whole file properly — through PSI, in a
 * read action, for a scan that otherwise never leaves the background thread it started on.
 *
 * What it does not do:
 *
 *  - **dotted keys** (`a.b = 1`) — the key is kept whole rather than nesting a table.
 *  - **array-of-tables headers** (`[[a]]`) — read as an ordinary table, so repeats collapse.
 *  - **typed values** — numbers, dates and booleans stay as their source text; the callers here
 *    only ever ask for strings.
 *
 * None of that is reachable from an aliases table, whose values are a string, an array of strings,
 * or an inline table with a `cmd`.
 */
internal object ByToml {

    fun parse(text: String): List<ByTomlSection> {
        val sections = mutableListOf<ByTomlSection>()
        var path = emptyList<String>()
        var sectionLine = 0
        var entries = mutableListOf<ByTomlEntry>()

        fun flush() {
            if (path.isNotEmpty() || entries.isNotEmpty()) sections += ByTomlSection(path, sectionLine, entries)
        }

        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val number = index
            val line = stripComment(lines[index]).trim()
            index++
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                flush()
                path = header(line)
                sectionLine = number
                entries = mutableListOf()
                continue
            }
            val separator = keySeparator(line) ?: continue
            var raw = line.substring(separator + 1).trim()
            // A value that opens a bracket, a brace or a triple quote and does not close it goes on
            // over the lines that follow; without joining them an array of tools would read as `[`.
            while (index < lines.size && !isComplete(raw)) {
                raw += "\n" + stripComment(lines[index]).trim()
                index++
            }
            entries += ByTomlEntry(unquote(line.substring(0, separator).trim()), raw, number)
        }
        flush()
        return sections
    }

    /** The entries of the table at [path], or an empty list when the file has no such table. */
    fun table(sections: List<ByTomlSection>, vararg path: String): List<ByTomlEntry> =
        sections.firstOrNull { it.path == path.toList() }?.entries.orEmpty()

    /** True when a table at or under [path] exists — how a `[tool.pyprojectx]` project is spotted. */
    fun hasTable(sections: List<ByTomlSection>, vararg path: String): Boolean {
        val prefix = path.toList()
        return sections.any { it.path.size >= prefix.size && it.path.subList(0, prefix.size) == prefix }
    }

    /** `[tool.pyprojectx.aliases]` → the three names. Quoted segments keep their spelling. */
    private fun header(line: String): List<String> {
        val body = line.trim().trim('[', ']').trim()
        return splitDotted(body).map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    /** The index of the `=` that ends a key, or null when the line has none outside a string. */
    private fun keySeparator(line: String): Int? {
        var quote = ' '
        line.forEachIndexed { index, ch ->
            when {
                quote != ' ' -> if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> quote = ch
                ch == '=' -> return index
            }
        }
        return null
    }

    /** True when every bracket, brace and quote [raw] opened is closed again. */
    private fun isComplete(raw: String): Boolean {
        var depth = 0
        var quote = ' '
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            val triple = index + 2 < raw.length && raw.startsWith(ch.toString().repeat(3), index)
            when {
                quote != ' ' -> if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> {
                    if (triple) {
                        val close = raw.indexOf(ch.toString().repeat(3), index + 3)
                        if (close < 0) return false
                        index = close + 2
                    } else {
                        quote = ch
                    }
                }
                ch == '[' || ch == '{' -> depth++
                ch == ']' || ch == '}' -> depth--
            }
            index++
        }
        return depth <= 0 && quote == ' '
    }

    private fun stripComment(raw: String): String {
        var quote = ' '
        raw.forEachIndexed { index, ch ->
            when {
                quote != ' ' -> if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' -> return raw.substring(0, index)
            }
        }
        return raw
    }

    /** The items of a `[…]` or `{…}`, split on the commas that are not inside anything. */
    fun splitFlow(raw: String): List<String> {
        val body = raw.trim().let { it.substring(1, (it.length - 1).coerceAtLeast(1)) }
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quote = ' '
        var depth = 0
        for (ch in body) {
            when {
                quote != ' ' -> {
                    if (ch == quote) quote = ' '
                    current.append(ch)
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == '[' || ch == '{' -> {
                    depth++
                    current.append(ch)
                }
                ch == ']' || ch == '}' -> {
                    depth--
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    items += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        items += current.toString()
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A dotted key split on the dots that are outside quotes. */
    private fun splitDotted(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quote = ' '
        for (ch in text) {
            when {
                quote != ' ' -> {
                    if (ch == quote) quote = ' '
                    current.append(ch)
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == '.' -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts
    }

    /** [text] with its quotes taken off, triple-quoted values included. */
    fun unquote(text: String): String {
        val trimmed = text.trim()
        for (quote in listOf("\"\"\"", "'''")) {
            if (trimmed.length >= 6 && trimmed.startsWith(quote) && trimmed.endsWith(quote)) {
                return trimmed.substring(3, trimmed.length - 3).trim('\n')
            }
        }
        if (trimmed.length < 2) return trimmed
        val quote = trimmed.first()
        if (quote != trimmed.last() || (quote != '"' && quote != '\'')) return trimmed
        val body = trimmed.substring(1, trimmed.length - 1)
        // A literal (single-quoted) string has no escapes at all; a basic one has these.
        if (quote == '\'') return body
        return body
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
