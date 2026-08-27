package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.util.text.StringUtil

/**
 * A docstring shown as what it is — text — for the one case `by` cannot be asked about.
 *
 * A module docstring documents the file, and hover needs a name; see [ByRenderedDocs]. Everything
 * else in a file goes through the server, and text never reaches here.
 *
 * The rule is that nothing is interpreted. That sounds like a weaker version of rendering it as
 * markdown, and it is the opposite: a raw docstring is not markdown, and reading one as markdown
 * does not degrade, it invents. `>>> int('0b100', base=0)` is three levels of blockquote to a
 * markdown parser, and comes out as nested vertical rules with the `>>>` eaten and the expected
 * output pulled onto the same line — where `by`, which knows a doctest when it sees one, would have
 * fenced it. Four-space indentation is a code block for the same reason. Text has neither problem.
 *
 * Two things are still done, because without them the text does not read as text at all:
 *  - **PEP 257's indentation trim.** A docstring's first line begins after the quotes at column
 *    zero while its later lines carry the indentation of the code around them, so nothing lines up
 *    until the shared indent is gone. The first line is trimmed on its own, per the PEP.
 *  - **Blank lines become paragraph breaks**, and the line breaks inside a paragraph are kept, so a
 *    docstring laid out in columns stays in columns.
 */
internal object ByDocstringText {

    /** The docstring literal — quotes, prefix and all — as an HTML block, or `null` if it is empty. */
    fun html(literal: String): String? {
        val trimmed = trimIndentation(body(literal))
        if (trimmed.isBlank()) return null
        return trimmed.split(PARAGRAPH_BREAK)
            .filter { it.isNotBlank() }
            .joinToString("") { paragraph -> "<p>${paragraph.trimEnd().lines().joinToString(separator = "<br/>", transform = ::line)}</p>" }
    }

    private val PARAGRAPH_BREAK = Regex("\n[ \t]*\n")

    /** Leading spaces have to be spelled out, or HTML collapses the shape the trim just preserved. */
    private fun line(text: String): String {
        val indent = text.takeWhile { it == ' ' }.length
        return "&nbsp;".repeat(indent) + StringUtil.escapeXmlEntities(text.substring(indent))
    }

    /** PEP 257's rule: the first line stands alone, the rest lose the indentation they share. */
    fun trimIndentation(body: String): String {
        val lines = body.replace("\t", "        ").trimEnd().lines()
        val first = lines.firstOrNull()?.trim() ?: return ""
        val shared = lines.drop(1)
            .filter { it.isNotBlank() }
            .minOfOrNull { line -> line.takeWhile { it == ' ' }.length }
            ?: 0
        val rest = lines.drop(1).map { it.drop(minOf(shared, it.length)).trimEnd() }
        return (listOf(first) + rest).joinToString("\n").trim('\n')
    }

    /**
     * The literal's contents, with its prefix and quotes removed.
     *
     * An unterminated literal — which the server will happily classify while the file is being
     * typed — loses its opening quotes and keeps whatever followed.
     */
    fun body(literal: String): String {
        val text = literal.dropWhile { it.isLetter() }
        for (quote in listOf("\"\"\"", "'''")) {
            if (text.startsWith(quote)) {
                val inner = text.substring(3)
                return if (inner.length >= 3 && inner.endsWith(quote)) inner.dropLast(3) else inner
            }
        }
        val quote = text.firstOrNull()
        if (quote == '"' || quote == '\'') {
            val inner = text.substring(1)
            return if (inner.isNotEmpty() && inner.last() == quote) inner.dropLast(1) else inner
        }
        return text
    }
}
