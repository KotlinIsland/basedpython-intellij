package dev.basedpython.pycharm.console

import com.intellij.execution.filters.Filter

/**
 * Links each `<by-input-N>` in a console's output — a traceback frame in an input, a diagnostic
 * against one — to that input's line in the console's history, where it was typed.
 *
 * An input scrolled out of the history, or one from before the console was cleared, is not linked.
 */
internal class ByConsoleInputFilter(private val session: ByConsoleSession) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineStart = entireLength - line.length
        val items = ByConsoleText.inputLinks(line).mapNotNull { link ->
            val offset = session.historyOffset(link.input, link.line, link.column) ?: return@mapNotNull null
            Filter.ResultItem(lineStart + link.range.startOffset, lineStart + link.range.endOffset, session.historyLink(offset))
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
