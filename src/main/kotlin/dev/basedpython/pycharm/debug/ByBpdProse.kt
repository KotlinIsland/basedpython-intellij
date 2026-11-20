package dev.basedpython.pycharm.debug

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil

/**
 * bpd's sentences as the HTML every surface that shows them reads.
 *
 * bpd writes plain text with two conventions of its own: a name or a piece of code between
 * backticks, and **emphasis** between double asterisks. Every surface this plugin hands one of its
 * sentences to reads its string as HTML instead — a notification's content, an error hint, a
 * breakpoint's tooltip — so a sentence passed as it came lost every `<module>`, `<lambda>` and
 * `<locals>` in it as a tag, measured on a refused Reset Frame that read "runs `` and cannot be run
 * again", and showed its backticks and asterisks as characters.
 *
 * Not a markdown renderer, deliberately. bpd's text is not markdown: a bare `<` in it is a less-than
 * sign, never inline HTML, and a markdown renderer would pass `a <b> c` through as a tag. So
 * everything is escaped first, and only the two conventions bpd uses are turned back into markup.
 */
internal object ByBpdProse {

    /** One code span: a run of backticks, the code, and the same run again, as CommonMark has it. */
    private val CODE = Regex("(`+)(.+?)\\1", RegexOption.DOT_MATCHES_ALL)

    /** Emphasis, outside code. Non-greedy, so two emphasised phrases on a line stay two. */
    private val STRONG = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)

    /**
     * [text] as an HTML fragment, with no `<html>` around it: the surfaces that take one add their
     * own. A line break is kept as `<br>`, since HTML would fold it into a space.
     */
    fun html(text: String): @NlsSafe String = buildString {
        var at = 0
        for (code in CODE.findAll(text)) {
            append(prose(text.substring(at, code.range.first)))
            append("<code>").append(StringUtil.escapeXmlEntities(code.groupValues[2])).append("</code>")
            at = code.range.last + 1
        }
        append(prose(text.substring(at)))
    }

    /** Text outside code: escaped, its emphasis made bold and its line breaks kept. */
    private fun prose(text: String): String =
        STRONG.replace(StringUtil.escapeXmlEntities(text)) { "<b>${it.groupValues[1]}</b>" }
            .replace("\n", "<br>")
}
