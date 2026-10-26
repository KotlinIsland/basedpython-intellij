package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.project.Project

/**
 * An LSP snippet, `for ${1:item} in xs:\n    $0`, read into the text it writes and the places the
 * caret stops at, and made into a live template — what the platform's LSP completion does with a
 * snippet item, for the completions this plugin asks `by` for itself ([BySmartCompletion]), which the
 * platform's own machinery is not public for.
 */
internal object BySnippets {

    /** A run of text, or a tab stop: [index] 0 is where the caret ends, the rest are stopped at in order. */
    sealed interface Part {
        data class Text(val text: String) : Part
        data class Stop(val index: Int, val placeholder: String) : Part
    }

    /**
     * [snippet] read as LSP's snippet syntax says: `$1` and `${1}` are tab stops, `${1:text}` one with
     * a placeholder, `${1|one,two|}` one whose placeholder is its first choice, and `\` escapes `$`, `}`
     * and `\`. A nested stop inside a placeholder is its placeholder text. A variable, `$TM_FILENAME`,
     * which `by` never writes, is written as it is.
     */
    fun parse(snippet: String): List<Part> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = 0
        fun flush() { if (text.isNotEmpty()) { parts += Part.Text(text.toString()); text.clear() } }
        while (i < snippet.length) {
            val c = snippet[i]
            if (c == '\\' && i + 1 < snippet.length && snippet[i + 1] in "$}\\") { text.append(snippet[i + 1]); i += 2; continue }
            if (c != '$' || i + 1 >= snippet.length) { text.append(c); i++; continue }
            val next = snippet[i + 1]
            if (next.isDigit()) {
                var end = i + 1
                while (end < snippet.length && snippet[end].isDigit()) end++
                flush(); parts += Part.Stop(snippet.substring(i + 1, end).toInt(), ""); i = end; continue
            }
            if (next == '{' && i + 2 < snippet.length && snippet[i + 2].isDigit()) {
                var end = i + 2
                while (end < snippet.length && snippet[end].isDigit()) end++
                val index = snippet.substring(i + 2, end).toInt()
                val close = closingBrace(snippet, end) ?: run { text.append(c); i++; continue }
                val body = snippet.substring(end, close)
                val placeholder = when {
                    body.startsWith(":") -> parse(body.substring(1)).joinToString("") { (it as? Part.Text)?.text ?: (it as Part.Stop).placeholder }
                    body.startsWith("|") && body.endsWith("|") -> body.substring(1, body.length - 1).split(',').first()
                    else -> ""
                }
                flush(); parts += Part.Stop(index, placeholder); i = close + 1; continue
            }
            text.append(c); i++
        }
        flush()
        return parts
    }

    /** The `}` that closes the `${` whose body starts at [from], nested ones skipped, escapes honoured. */
    private fun closingBrace(snippet: String, from: Int): Int? {
        var depth = 0
        var i = from
        while (i < snippet.length) {
            when {
                snippet[i] == '\\' -> i++
                snippet[i] == '$' && i + 1 < snippet.length && snippet[i + 1] == '{' -> { depth++; i++ }
                snippet[i] == '}' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return null
    }

    /**
     * [parts] as a live template: each tab stop a variable stopped at in its index's order, its later
     * occurrences mirroring it, and `$0` where the caret ends. The platform indents the template's
     * lines by the line it is inserted on, as LSP's `adjustIndentation` says a snippet's are.
     */
    fun template(project: Project, parts: List<Part>): Template {
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        val stops = parts.filterIsInstance<Part.Stop>().filter { it.index > 0 }
            .groupBy { it.index }.toSortedMap()
        for ((index, occurrences) in stops) {
            val placeholder = occurrences.first().placeholder
            template.addVariable(variable(index), ConstantNode(placeholder), ConstantNode(placeholder), true)
        }
        for (part in parts) when (part) {
            is Part.Text -> template.addTextSegment(part.text)
            is Part.Stop -> if (part.index == 0) template.addEndVariable() else template.addVariableSegment(variable(part.index))
        }
        template.isToReformat = false
        return template
    }

    private fun variable(index: Int) = "STOP_$index"
}
