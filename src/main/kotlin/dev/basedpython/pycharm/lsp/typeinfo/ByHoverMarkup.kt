package dev.basedpython.pycharm.lsp.typeinfo

import com.intellij.openapi.util.text.StringUtil

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
        parse(markup).firstOrNull()?.let { toHtml(it.text) }

    /** The whole payload — type and docstring — as HTML for the second Ctrl+Shift+P press. */
    fun fullHtml(markup: String): String? {
        val blocks = parse(markup)
        if (blocks.isEmpty()) return null
        return blocks.joinToString("<hr/>") { toHtml(it.text) }
    }

    /**
     * Escapes [text] for a hint label and keeps its shape: `by` renders types multi-line (its
     * display settings ask for it), and a long union collapsed onto one line is unreadable.
     */
    private fun toHtml(text: String): String =
        StringUtil.escapeXmlEntities(text)
            .lines()
            .joinToString("<br/>") { line ->
                // Leading indentation is significant in a multi-line type; plain spaces collapse.
                val indent = line.takeWhile { it == ' ' }.length
                "&nbsp;".repeat(indent) + line.substring(indent)
            }
}
