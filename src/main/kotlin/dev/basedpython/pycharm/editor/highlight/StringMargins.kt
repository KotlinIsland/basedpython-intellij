package dev.basedpython.pycharm.editor.highlight

import dev.basedpython.pycharm.lsp.outline.ByOutline

/**
 * Where a multiline string's stripped indentation ends, laid out on the lines of its literal.
 *
 * Offsets are into the document text the margin was measured in.
 */
data class StringMargin(
    /** The literal this margin belongs to, prefix and quotes included. */
    val literalStart: Int,
    val literalEnd: Int,
    /** How many leading whitespace characters come off every line of the literal's content. */
    val indent: Int,
    /** Start of the first line the margin is drawn on — the line after the opening quotes. */
    val firstLineStart: Int,
    /**
     * Start of the last line it is drawn on: the last line of content.
     *
     * The closing quotes are on a line of their own below it — basedpython strips nothing from a
     * literal whose quotes are not — and the rule stops above them rather than running down beside
     * them, so it points at the literal's end instead of overshooting into whatever follows.
     */
    val lastLineStart: Int,
    /**
     * Where the margin is drawn: [indent] characters into a line of content, every one of which
     * starts with exactly the whitespace that is stripped.
     *
     * A column would not be enough. Which pixel column an indent lands on depends on the characters
     * before it — a tab is one character and several columns wide — so the line is placed by asking
     * the editor where this offset is.
     */
    val anchorOffset: Int,
)

/**
 * The trim margin of every multiline string basedpython strips: what it strips, drawn where it
 * strips it.
 *
 * basedpython dedents a triple-quoted string the way Java dedents a text block, and that is a rule
 * you cannot see. In Python the literal *is* its own content; in basedpython the indentation the
 * content lines share belongs to the code's layout, not to the string, and is removed. Java has the
 * same rule and IntelliJ IDEA answers it with a vertical line in the text block; this is that line
 * for `.by`.
 *
 * *Whether* a literal is stripped, and by how much, is `by`'s answer ([ByOutline.StringPart.strippedIndent]),
 * because it is the transpiler's rule and nobody else's to restate: only a lone triple-quoted literal
 * whose content opens on the line after its quotes and whose closing quotes sit on a line of their own
 * is dedented, by the indentation its non-blank content lines share. A docstring that starts on its
 * opening line is left as written, and so is one whose closing quotes are indented past the content.
 * What is left here is placing that answer on the lines of the literal.
 *
 * A margin of zero is never reported: nothing is stripped from such a literal.
 */
object StringMargins {

    /** The literals in [outline] that basedpython strips, and by how much, in source order. */
    fun strippedIn(outline: ByOutline): List<ByOutline.StringPart> =
        outline.strings.filter { (it.strippedIndent ?: 0) > 0 }

    /**
     * The margin of the literal at `[start, end)` in [text], stripped by [indent] characters, or
     * null when the literal no longer has the shape a margin is drawn in — which happens between an
     * edit and the next answer from `by`, and is then not drawn rather than drawn somewhere wrong.
     */
    fun marginOf(text: CharSequence, start: Int, end: Int, indent: Int): StringMargin? {
        if (indent <= 0 || start < 0 || end > text.length || start >= end) return null
        val firstLineStart = text.indexOfNewline(start, end)?.plus(1) ?: return null
        val closingLineStart = text.lastNewlineBefore(end, firstLineStart)?.plus(1) ?: return null
        if (closingLineStart <= firstLineStart) return null
        val lastLineStart = text.lastNewlineBefore(closingLineStart - 1, firstLineStart)?.plus(1)
            ?: firstLineStart

        // The first line of content that is not blank starts with the stripped whitespace; blank
        // lines are erased whole and need not be as long as the indent.
        var lineStart = firstLineStart
        while (lineStart < closingLineStart) {
            val lineEnd = text.indexOfNewline(lineStart, closingLineStart) ?: closingLineStart
            if ((lineStart until lineEnd).any { text[it] != ' ' && text[it] != '\t' }) {
                if (lineEnd - lineStart < indent) return null
                if ((lineStart until lineStart + indent).any { text[it] != ' ' && text[it] != '\t' }) return null
                return StringMargin(start, end, indent, firstLineStart, lastLineStart, lineStart + indent)
            }
            lineStart = lineEnd + 1
        }
        return null
    }

    /**
     * The next `\n` in `[from, limit)`, or null.
     *
     * `\n` alone, with no `\r` case: a [com.intellij.openapi.editor.Document] holds only line feeds
     * whatever the file on disk uses, and a document is what this reads.
     */
    private fun CharSequence.indexOfNewline(from: Int, limit: Int): Int? {
        for (i in from until limit) if (this[i] == '\n') return i
        return null
    }

    /** The last `\n` in `[floor - 1, before)`, or null. */
    private fun CharSequence.lastNewlineBefore(before: Int, floor: Int): Int? {
        for (i in before - 1 downTo maxOf(floor - 1, 0)) if (this[i] == '\n') return i
        return null
    }
}
