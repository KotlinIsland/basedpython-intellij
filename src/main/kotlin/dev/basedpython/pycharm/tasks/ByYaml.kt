package dev.basedpython.pycharm.tasks

/**
 * A value read out of a hook configuration, keeping the line it was written on.
 *
 * The line is why this exists as a tree rather than as a `Map<String, Any>`: every task in the view
 * offers *Jump to Source*, and the only thing that can answer where a hook is declared is the parse
 * that found it.
 */
internal sealed interface ByYamlValue {
    /** 0-based line the value starts on. */
    val line: Int

    data class Scalar(val text: String, override val line: Int) : ByYamlValue

    data class Mapping(val entries: List<ByYamlEntry>, override val line: Int) : ByYamlValue

    data class Sequence(val items: List<ByYamlValue>, override val line: Int) : ByYamlValue
}

/** One `key: value` of a mapping, in the order it was written. */
internal data class ByYamlEntry(val key: String, val value: ByYamlValue, val line: Int)

/** The value of [key], or null when the mapping has no such key. */
internal fun ByYamlValue.Mapping.value(key: String): ByYamlValue? =
    entries.firstOrNull { it.key == key }?.value

/** The entry for [key] — [value] plus the line the key itself is on. */
internal fun ByYamlValue.Mapping.entry(key: String): ByYamlEntry? =
    entries.firstOrNull { it.key == key }

/** This value as a mapping, or null. */
internal fun ByYamlValue?.asMapping(): ByYamlValue.Mapping? = this as? ByYamlValue.Mapping

/** This value's text when it is a scalar, blank ones included as null. */
internal fun ByYamlValue?.text(): String? =
    (this as? ByYamlValue.Scalar)?.text?.takeIf { it.isNotBlank() }

/** This value's items when it is a sequence; a lone scalar counts as a one-item sequence. */
internal fun ByYamlValue?.items(): List<ByYamlValue> = when (this) {
    is ByYamlValue.Sequence -> items
    null -> emptyList()
    else -> listOf(this)
}

/** [items] as text, dropping anything that is not a scalar. */
internal fun ByYamlValue?.strings(): List<String> = items().mapNotNull { it.text() }

/**
 * The slice of YAML that hook configurations are actually written in.
 *
 * Deliberately not a YAML implementation. `.pre-commit-config.yaml` and `lefthook.yml` are
 * hand-written config: block mappings, block sequences, flow sequences of stage names, and the
 * occasional `run: |` script. Reading them takes the parser below; reading *YAML* would take
 * anchors, aliases, tags, multi-document streams and folded-scalar chomping semantics, none of
 * which either tool's schema has any use for.
 *
 * The alternative was the platform's own YAML plugin, which is a real parser — and a PSI one, so
 * every read would need a read action and a `PsiFile`, for a scan that otherwise touches nothing
 * but four files on disk. The choice here is a parser that is pure, unit-testable against literal
 * config text, and honest about what it does not do:
 *
 *  - **anchors, aliases, tags, multi-document streams** — ignored; `&x`, `*x` and `!!str` are read
 *    as ordinary scalar text and `---` separators are skipped, so a stream's documents merge.
 *  - **quoted scalars spanning lines** — not joined; the first line is the value.
 *  - **block scalars** (`|`, `>`) — kept as their lines joined by newlines, with the indentation
 *    already stripped and any comment-only line inside them dropped. Enough for the grey `run:`
 *    preview a task row shows, not enough to reconstruct a script byte for byte.
 *  - **flow collections spanning lines** — a `[` or `{` is closed on its own line or not at all.
 *
 * Nothing above changes which tasks are found, which is what this is for: a hook is a `- id:` under
 * `repos:`, and a lefthook command is a key under `commands:`, in every configuration either tool
 * documents.
 */
internal object ByYaml {

    /** The document as a mapping; a file that is not one (empty, a bare list) yields no entries. */
    fun parse(text: String): ByYamlValue.Mapping {
        val lines = scan(text)
        if (lines.isEmpty()) return ByYamlValue.Mapping(emptyList(), 0)
        return Parser(lines).parseBlock(lines.first().indent).asMapping()
            ?: ByYamlValue.Mapping(emptyList(), lines.first().number)
    }

    /** One meaningful line: its indentation, its content, and where it came from. */
    private data class Line(val indent: Int, val text: String, val number: Int) {
        /** True for a block-sequence entry — `- x`, or a bare `-` with the item indented under it. */
        val isItem: Boolean get() = text.startsWith("-") && (text.length == 1 || text[1] == ' ')
    }

    /** Drops what carries no structure — blanks, comments, document markers — and measures the rest. */
    private fun scan(text: String): List<Line> {
        val lines = mutableListOf<Line>()
        text.lines().forEachIndexed { number, raw ->
            val withoutComment = stripComment(raw)
            val content = withoutComment.trim()
            if (content.isEmpty() || content == "---" || content == "...") return@forEachIndexed
            lines += Line(indent = withoutComment.indexOfFirst { !it.isWhitespace() }, text = content, number = number)
        }
        return lines
    }

    /**
     * [raw] up to its comment.
     *
     * A `#` only starts one at the beginning of the line or after whitespace — `run: git rev-parse
     * --short=8 HEAD#tag` is a value, not a comment — and never inside quotes.
     */
    private fun stripComment(raw: String): String {
        var quote = ' '
        raw.forEachIndexed { index, ch ->
            when {
                quote != ' ' -> if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' && (index == 0 || raw[index - 1].isWhitespace()) -> return raw.substring(0, index)
            }
        }
        return raw
    }

    private class Parser(lines: List<Line>) {

        /**
         * Mutable so a sequence item can be rewritten as the mapping line it contains.
         *
         * `- id: black` is a dash and a mapping that starts in the same line, and the lines under it
         * belong to that mapping. Rewriting the line to `id: black` at the column the `i` sits in
         * turns that into the ordinary case the mapping parser already handles, instead of a second
         * "mapping, but the first entry came from over there" code path.
         */
        private val lines = lines.toMutableList()
        private var at = 0

        private fun peek(): Line? = lines.getOrNull(at)

        /** The block starting at the current line, which its first line's shape decides. */
        fun parseBlock(indent: Int): ByYamlValue {
            val first = peek() ?: return ByYamlValue.Scalar("", 0)
            return if (first.isItem) parseSequence(indent) else parseMapping(indent)
        }

        private fun parseSequence(indent: Int): ByYamlValue.Sequence {
            val start = peek()?.number ?: 0
            val items = mutableListOf<ByYamlValue>()
            while (true) {
                val line = peek() ?: break
                if (line.indent != indent || !line.isItem) break
                val rest = line.text.substring(1).trimStart()
                val contentIndent = line.indent + (line.text.length - rest.length)
                if (rest.isEmpty()) {
                    at++
                    val next = peek()
                    items += if (next != null && next.indent > indent) {
                        parseBlock(next.indent)
                    } else {
                        ByYamlValue.Scalar("", line.number)
                    }
                    continue
                }
                // The item's own content, re-indented to the column it starts at; see [lines].
                lines[at] = Line(contentIndent, rest, line.number)
                items += if (splitKey(rest) != null) {
                    parseBlock(contentIndent)
                } else {
                    at++
                    parseValue(rest, line.number, contentIndent)
                }
            }
            return ByYamlValue.Sequence(items, start)
        }

        private fun parseMapping(indent: Int): ByYamlValue.Mapping {
            val start = peek()?.number ?: 0
            val entries = mutableListOf<ByYamlEntry>()
            while (true) {
                val line = peek() ?: break
                if (line.indent < indent || (line.indent == indent && line.isItem)) break
                // Deeper than the mapping and not claimed by an entry above: a continuation this
                // parser does not model. Skipped rather than treated as an entry of this mapping,
                // which would hoist it a level and invent a key.
                if (line.indent > indent) {
                    at++
                    continue
                }
                val split = splitKey(line.text)
                if (split == null) {
                    at++
                    continue
                }
                at++
                val (key, raw) = split
                entries += ByYamlEntry(unquote(key), parseValue(raw, line.number, indent), line.number)
            }
            return ByYamlValue.Mapping(entries, start)
        }

        /** The value written after a `key:`, which may be on this line or in the block under it. */
        private fun parseValue(raw: String, number: Int, indent: Int): ByYamlValue = when {
            raw.isEmpty() -> nested(number, indent)
            raw.startsWith("[") -> ByYamlValue.Sequence(flowItems(raw).map { ByYamlValue.Scalar(it, number) }, number)
            raw.startsWith("{") -> flowMapping(raw, number)
            raw.startsWith("|") || raw.startsWith(">") -> blockScalar(number, indent)
            else -> ByYamlValue.Scalar(unquote(raw), number)
        }

        /**
         * The block belonging to a key with nothing after its colon.
         *
         * A sequence is allowed to sit at the key's own indentation — the shape `repos:` is written
         * in about half the time — so "nothing is indented under this" is not the same question as
         * "this key has no value".
         */
        private fun nested(number: Int, indent: Int): ByYamlValue {
            val next = peek() ?: return ByYamlValue.Scalar("", number)
            return when {
                next.indent > indent -> parseBlock(next.indent)
                next.indent == indent && next.isItem -> parseSequence(indent)
                else -> ByYamlValue.Scalar("", number)
            }
        }

        /** Everything indented under a `|` or `>`, with that indentation removed. */
        private fun blockScalar(number: Int, indent: Int): ByYamlValue.Scalar {
            val body = mutableListOf<String>()
            while (true) {
                val line = peek() ?: break
                if (line.indent <= indent) break
                body += line.text
                at++
            }
            return ByYamlValue.Scalar(body.joinToString("\n"), number)
        }

        private fun flowMapping(raw: String, number: Int): ByYamlValue.Mapping {
            val entries = flowItems(raw).mapNotNull { item ->
                val split = splitKey(item) ?: return@mapNotNull null
                ByYamlEntry(unquote(split.first), ByYamlValue.Scalar(unquote(split.second), number), number)
            }
            return ByYamlValue.Mapping(entries, number)
        }
    }

    /**
     * [text] split at the colon that ends its key, or null when it has none.
     *
     * The colon has to be followed by a space or end the line, and must not be inside quotes or a
     * flow collection: `run: sed 's/a:b/c/'` is one value, and `{a: 1, b: 2}` is not a key called
     * `{a` — the caller decides that by looking at the first character, so this only has to not lie
     * about `- foo: bar` versus `- https://example.com`.
     */
    private fun splitKey(text: String): Pair<String, String>? {
        if (text.startsWith("[") || text.startsWith("{")) return null
        var quote = ' '
        var depth = 0
        text.forEachIndexed { index, ch ->
            when {
                quote != ' ' -> if (ch == quote) quote = ' '
                ch == '"' || ch == '\'' -> quote = ch
                ch == '[' || ch == '{' -> depth++
                ch == ']' || ch == '}' -> depth--
                ch == ':' && depth == 0 && (index == text.lastIndex || text[index + 1] == ' ') ->
                    return text.substring(0, index).trim() to text.substring(index + 1).trim()
            }
        }
        return null
    }

    /** The items of a `[…]` or `{…}`, split on the commas that are not inside anything. */
    private fun flowItems(raw: String): List<String> {
        val body = raw.trim().removeSurrounding("[", "]").let {
            if (it == raw.trim()) raw.trim().removeSurrounding("{", "}") else it
        }
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
        return items.map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    /** [text] with its quotes taken off, and the escapes a double-quoted scalar can carry undone. */
    fun unquote(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length < 2) return trimmed
        val quote = trimmed.first()
        if (quote != trimmed.last() || (quote != '"' && quote != '\'')) return trimmed
        val body = trimmed.substring(1, trimmed.length - 1)
        // A single-quoted scalar has exactly one escape: '' for a literal quote.
        if (quote == '\'') return body.replace("''", "'")
        return body
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
    }
}
