package dev.basedpython.pycharm.lsp.typeinfo

import dev.basedpython.pycharm.markup.ByCodeSpans

/**
 * Pure parsing of a `textDocument/hover` payload from the `by` server into the pieces the Type Info
 * action (Ctrl+Shift+P) shows.
 *
 * There is no "give me the type of this expression" request in the `by` LSP — the server's request
 * set is the standard one (`ty_server/src/server/api/requests/`), its only custom entries being the
 * `ty.printDebugInformation` command and its own registration ids. Hover *is* where the type lives,
 * and the server builds its payload type-first: the inferred type (or the call signature, or the
 * `TypedDict` key) comes first, then the docstring, joined by a horizontal rule. So the first block
 * of a hover is exactly what Type Info wants, and the whole payload is what the second press of
 * Ctrl+Shift+P ("advanced information") wants.
 *
 * Both markup kinds the server can emit parse the same way here, which is why this splits on blocks
 * rather than pattern-matching markdown:
 *  - markdown — the type is a fenced ```` ```python ```` block, the rule is `---`
 *  - plain text — the type is bare text, the rule is a long run of dashes
 *
 * The kind is the server's choice (it looks at `prefers_markdown_in_hover` from the client
 * capabilities the platform sends), so neither can be assumed.
 */
object ByHoverMarkup {

    /** One section of a hover payload: a fenced code block, or the prose between rules. */
    data class Block(val text: String, val isCode: Boolean)

    private val RULE = Regex("""^-{3,}$""")
    private val FENCE = Regex("""^`{3,}""")

    /**
     * Splits [markup] into its blocks, dropping empties.
     *
     * Fences win over rules: a `---` inside a code block is part of the code, and a docstring's own
     * `---` underline would otherwise cut the docstring in two.
     */
    fun parse(markup: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val current = StringBuilder()
        var inFence = false

        fun flush(isCode: Boolean) {
            val text = current.toString().trim('\n')
            if (text.isNotBlank()) blocks += Block(text, isCode)
            current.setLength(0)
        }

        for (line in markup.lines()) {
            val trimmed = line.trim()
            when {
                FENCE.containsMatchIn(trimmed) -> {
                    // Closing fence ends a code block; opening fence ends whatever prose preceded it.
                    flush(isCode = inFence)
                    inFence = !inFence
                }

                !inFence && RULE.matches(trimmed) -> flush(isCode = false)

                else -> {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line)
                }
            }
        }
        flush(isCode = inFence)
        return blocks
    }

    /**
     * The type line(s) as HTML for the hint, or `null` when the payload has nothing in it.
     *
     * `null` rather than an empty string so the caller can say *why* there is no type instead of
     * flashing a blank hint.
     */
    fun typeHtml(markup: String): String? =
        parse(markup).firstOrNull()?.let { toHtml(it) }

    /** The whole payload — type and docstring — as HTML for the second Ctrl+Shift+P press. */
    fun fullHtml(markup: String): String? {
        val blocks = parse(markup)
        if (blocks.isEmpty()) return null
        return blocks.joinToString("<hr/>") { toHtml(it) }
    }

    /**
     * The docstring half of a hover payload, still markdown.
     *
     * [parse] answers what the Type Info hint needs — the payload as flat blocks, fences and rules
     * spent. Rendered documentation needs the opposite: the docstring exactly as `by` wrote it,
     * lists, fences, tables and all, to hand to the platform's markdown converter. So this cuts the
     * payload rather than parsing it, and returns the tail untouched.
     *
     * The cut is after the leading type or signature block:
     *  - markdown — the type is the first fenced block, and the docstring follows a `---` rule
     *  - plain text — there is no fence, and the rule is a long run of dashes
     *
     * `null` when the payload has no such cut, which means the server answered with a type and no
     * docstring at all. That is worth distinguishing from an empty docstring: a caller looking at a
     * docstring it can see in the file has learnt the server did not recognise the symbol, and can
     * fall back rather than render nothing.
     */
    fun docstringMarkdown(markup: String): String? {
        val lines = markup.lines()
        val first = lines.indexOfFirst { it.isNotBlank() }
        if (first < 0) return null

        val opening = lines[first].trim()
        val afterType = if (FENCE.containsMatchIn(opening)) {
            val ticks = opening.takeWhile { it == '`' }.length
            val closing = Regex("""^`{$ticks,}\s*$""")
            val close = (first + 1 until lines.size).firstOrNull { closing.matches(lines[it].trim()) }
                ?: return null
            close + 1
        } else {
            val rule = (first until lines.size).firstOrNull { RULE.matches(lines[it].trim()) } ?: return null
            rule + 1
        }

        val rest = lines.drop(afterType).dropWhile { it.isBlank() }
        val docs = if (rest.firstOrNull()?.let { RULE.matches(it.trim()) } == true) rest.drop(1) else rest
        return docs.joinToString("\n").trim('\n').ifBlank { null }
    }

    /**
     * A block as HTML for a hint label, escaped and keeping its shape — `by` renders types
     * multi-line (its display settings ask for it), and a long union collapsed onto one line is
     * unreadable.
     *
     * A docstring's `backticks` become `<code>`; a code block's do not. Inside the fence the text
     * is python, where a backtick is a character in a string and nothing else, and the type is
     * already being shown as code.
     */
    private fun toHtml(block: Block): String =
        if (block.isCode) ByCodeSpans.escapedHtml(block.text) else ByCodeSpans.toHtml(block.text)
}
