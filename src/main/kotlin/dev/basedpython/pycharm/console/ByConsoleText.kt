package dev.basedpython.pycharm.console

import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.editor.indent.ByIndents

/**
 * The text a console sends and edits, kept apart from the console so each rule can be stated in a
 * test: how an input is framed for `by repl`, what Enter inserts for an input that goes on, and how a
 * selection sent from an editor is cut loose from its indentation.
 */
internal object ByConsoleText {

    /** What a terminal sends before a paste, and an editor before an input it sends whole. */
    const val PASTE_START = "\u001b[200~"

    /** What closes it. */
    const val PASTE_END = "\u001b[201~"

    /**
     * [input] as `by repl` reads one input whatever it holds — blank lines inside a function body
     * included — framed as a terminal frames a paste and followed by a newline
     * (`docs/basedpython/features/repl.md`, *in an editor*).
     */
    fun framed(input: String): String = PASTE_START + input + PASTE_END + "\n"

    /**
     * Whether `by repl` reads [input] as an input at all. A blank one is skipped without being run or
     * numbered, as python's own repl skips it, so the console sends nothing for it.
     */
    fun isInput(input: String): Boolean = input.isNotBlank()

    /** The text `by/inputStatus` is asked about for [input] when Enter is pressed: each line ends in a newline, the one Enter adds included. */
    fun statusText(input: String): String = input + "\n"

    /**
     * [text] with the indentation its lines share removed, as a block selected inside a function is
     * sent: as code at the top level of the session. Blank lines count for nothing.
     */
    fun dedented(text: String): String {
        val lines = text.lines()
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        if (indent == 0) return text
        return lines.joinToString("\n") { line -> if (line.isBlank()) line.trimStart(' ', '\t') else line.substring(indent) }
    }

    /**
     * What Enter does to an input that goes on: [lineBreak], worked out for the session module ending
     * in the input — whose input starts at [base] — applied to the input itself, broken at [caret].
     *
     * Returns the new text and where the caret goes. A part of the answer that falls before the input,
     * where the console cannot edit, is not applied.
     */
    fun broken(input: String, caret: Int, base: Int, lineBreak: ByIndents.LineBreak, whitespace: (Int) -> String): Edit {
        var text = input
        var at = lineBreak.at?.minus(base)?.takeIf { it in 0..input.length } ?: caret
        lineBreak.wrap?.let { wrap ->
            val start = wrap.startOffset - base
            val end = wrap.endOffset - base
            if (start >= 0 && end <= text.length) {
                text = text.substring(0, start) + "(" + text.substring(start, end) + ")" + text.substring(end)
                if (at > start) at++
            }
        }
        lineBreak.closeOnOwnLine?.let { closing ->
            var closer = at
            while (closer < text.length && (text[closer] == ' ' || text[closer] == '\t')) closer++
            val inner = whitespace(lineBreak.indent)
            return Edit(text.substring(0, at) + "\n" + inner + "\n" + whitespace(closing) + text.substring(closer), at + 1 + inner.length)
        }
        val inserted = lineBreak.before + "\n" + whitespace(lineBreak.indent) + lineBreak.after
        return Edit(text.substring(0, at) + inserted + text.substring(at), at + inserted.length)
    }

    /** A line broken where nothing knows better: the new line indented as the one the caret is on. */
    fun brokenPlainly(input: String, caret: Int): Edit {
        val lineStart = input.lastIndexOf('\n', caret - 1) + 1
        val indent = input.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        val inserted = "\n" + indent
        return Edit(input.substring(0, caret) + inserted + input.substring(caret), caret + inserted.length)
    }

    /** An input's new text and caret. */
    data class Edit(val text: String, val caret: Int)

    /** A place in an input that `by repl`'s output names: `<by-input-N>`, at a 1-based line and column. */
    data class InputLink(val range: TextRange, val input: Int, val line: Int, val column: Int)

    /**
     * Each `<by-input-N>` [line] names with a line: a traceback's `File "<by-input-2>", line 1` and a
     * diagnostic's `--> <by-input-4>:1:1`, the range being what is linked.
     */
    fun inputLinks(line: String): List<InputLink> = buildList {
        for (match in TRACEBACK.findAll(line)) {
            val name = match.groups[1]!!
            add(InputLink(TextRange(name.range.first, match.range.last + 1), match.groupValues[2].toInt(), match.groupValues[3].toInt(), 1))
        }
        for (match in DIAGNOSTIC.findAll(line)) {
            add(InputLink(TextRange(match.range.first, match.range.last + 1), match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()))
        }
    }

    private val TRACEBACK = Regex("""File "(<by-input-(\d+)>)", line (\d+)""")
    private val DIAGNOSTIC = Regex("""<by-input-(\d+)>:(\d+):(\d+)""")
}
