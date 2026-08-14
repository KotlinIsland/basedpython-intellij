package dev.basedpython.pycharm.markup

import com.intellij.openapi.util.text.StringUtil

/**
 * Renders the markdown code spans in a message from `by` as `<code>`, for the HTML the IDE's
 * tooltips, hints and balloons are made of.
 *
 * Every message the type checker writes names its types and symbols in backticks — ``Object of type
 * `Literal[1]` is not callable``, ``Argument to bound method `f` is incorrect`` — because that is
 * how `ty` formats them, and the LSP hands the message over exactly as written. A diagnostic
 * tooltip is HTML, where a backtick means nothing, so those backticks used to reach the user as
 * backticks — and a message naming a type the IDE reads as a tag, `<class 'int'>` among them, lost
 * that fragment to Swing's HTML parser entirely.
 *
 * ## Why this cannot be exact
 *
 * A code span is delimited by a run of backticks, and nothing escapes the backticks that come out
 * of the code being checked, so a string-literal type carrying one is indistinguishable from a
 * delimiter:
 *
 * ```text
 * Type `Literal["`"]` is not assignable to `str`
 * ```
 *
 * Two spans and a stray backtick would have produced the same characters, and no parse of that line
 * alone can tell which was meant. So the aim here is not soundness but damage control: the text is
 * never altered or dropped, which makes the worst case a fragment styled as prose that should have
 * been code, or the reverse — never a message that reads wrong.
 *
 * Three rules, in the order of how much they buy:
 *  - **A run matches a run of the same length**, as CommonMark has it, so ``` ``Literal["`"]`` ```
 *    is one span whenever the producer does escape.
 *  - **A span never crosses a line**, so an unpaired backtick can only spoil the line it is on
 *    instead of swallowing the rest of a multi-line message.
 *  - **A closer that would leave a `"` open is passed over** in favour of the next one. That is the
 *    whole of the `Literal["`"]` case above: `Literal["` is not a type the checker can have meant to
 *    name, `Literal["`"]` is, and preferring the closer that terminates the string recovers the
 *    intended pair. When no closer terminates it — a message about the `"` character itself — the
 *    nearest one is used, which is what CommonMark would have done anyway.
 */
object ByCodeSpans {

    /** One piece of a message: the content of a code span, or the prose around it. */
    data class Span(val text: String, val isCode: Boolean)

    /**
     * Splits [text] into its code spans and the prose between them.
     *
     * Prose carries the line breaks; a code span never does, per the rule above.
     */
    fun spans(text: String): List<Span> {
        if ('`' !in text) return if (text.isEmpty()) emptyList() else listOf(Span(text, isCode = false))

        val spans = mutableListOf<Span>()
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isNotEmpty()) {
                spans += Span(prose.toString(), isCode = false)
                prose.setLength(0)
            }
        }

        text.lines().forEachIndexed { index, line ->
            if (index > 0) prose.append('\n')
            val runs = backtickRuns(line)
            var cursor = 0
            var next = 0
            while (next < runs.size) {
                val open = runs[next]
                val close = closerFor(line, runs, next)
                if (close < 0) {
                    // Unpaired: the run is text, and the message keeps the backticks it really had.
                    prose.append(line, cursor, open.end)
                    cursor = open.end
                    next++
                    continue
                }
                prose.append(line, cursor, open.start)
                flushProse()
                spans += Span(content(line.substring(open.end, runs[close].start)), isCode = true)
                cursor = runs[close].end
                next = close + 1
            }
            prose.append(line, cursor, line.length)
        }
        flushProse()
        return spans
    }

    /** [text] as HTML, its code spans marked up as such. */
    fun toHtml(text: String): String = render(spans(text))

    /**
     * [text] as HTML with no code spans read out of it — for the places that already know their
     * text is code, where a backtick is a character rather than a delimiter.
     */
    fun escapedHtml(text: String): String = render(listOf(Span(text, isCode = false)))

    private fun render(spans: List<Span>): String {
        val html = StringBuilder()
        var atLineStart = true
        for (span in spans) {
            if (span.isCode) {
                html.append("<code>").append(StringUtil.escapeXmlEntities(span.text)).append("</code>")
                atLineStart = false
                continue
            }
            span.text.split('\n').forEachIndexed { index, line ->
                if (index > 0) {
                    html.append("<br/>")
                    atLineStart = true
                }
                html.append(lineHtml(line, atLineStart))
                if (line.isNotEmpty()) atLineStart = false
            }
        }
        return html.toString()
    }

    /**
     * Escapes one line, keeping its shape: `by` renders types multi-line by choice and indents the
     * continuations, and plain spaces collapse in HTML.
     *
     * [atLineStart] is false for the remainder of a line that a code span has already begun, where
     * the spaces are between words rather than in front of them.
     */
    private fun lineHtml(line: String, atLineStart: Boolean): String {
        if (!atLineStart) return StringUtil.escapeXmlEntities(line)
        val indent = line.takeWhile { it == ' ' }.length
        return "&nbsp;".repeat(indent) + StringUtil.escapeXmlEntities(line.substring(indent))
    }

    /** Half-open, so [end] is where the run's content starts. */
    private data class Run(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    private fun backtickRuns(line: String): List<Run> {
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                i++
                continue
            }
            val start = i
            while (i < line.length && line[i] == '`') i++
            runs += Run(start, i)
        }
        return runs
    }

    /**
     * The index of the run that closes the one at [openIndex], or -1 when nothing does.
     *
     * The nearest run of the same length closes it, except while that would cut a `"` string in
     * half — see the class comment. A candidate that is passed over is still the answer if no later
     * one is any better, so an unbalanced quote costs nothing when it is simply what the message is
     * about.
     */
    private fun closerFor(line: String, runs: List<Run>, openIndex: Int): Int {
        val open = runs[openIndex]
        var nearest = -1
        for (i in openIndex + 1 until runs.size) {
            if (runs[i].length != open.length) continue
            if (nearest < 0) nearest = i
            if (quotesClosed(line.substring(open.end, runs[i].start))) return i
        }
        return nearest
    }

    /** Whether every `"` in [content] is matched — backslash escapes included, as `ty` writes them. */
    private fun quotesClosed(content: String): Boolean {
        var open = false
        var i = 0
        while (i < content.length) {
            when {
                open && content[i] == '\\' -> i++
                content[i] == '"' -> open = !open
            }
            i++
        }
        return !open
    }

    /** CommonMark drops one space from each end, so `` ` `` can be written as ``` `` ` `` ```. */
    private fun content(raw: String): String =
        if (raw.length > 1 && raw.startsWith(' ') && raw.endsWith(' ') && raw.isNotBlank()) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
}
