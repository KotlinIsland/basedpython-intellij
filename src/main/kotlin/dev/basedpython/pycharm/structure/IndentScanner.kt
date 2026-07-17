package dev.basedpython.pycharm.structure

/**
 * Shared helper: scans basedpython/.by file text and returns a flat list of [ScopeNode]s
 * ordered by start offset. Used by structure view, folding builder, and breadcrumbs.
 *
 * Detection rules (indentation-based, no composite PSI required):
 * - `def`, `async def` → FUNCTION
 * - `class`, `class def`, `data class`, `frozen data class`, `enum class`, `protocol` → CLASS
 * - top-level `<name> =` / `<name>: ...` lines (indent == 0) → FIELD
 * - `import` / `from … import` consecutive blocks → IMPORT_BLOCK (run of import lines)
 * - `# region` / `# endregion` → REGION / REGION_END markers
 */
object IndentScanner {

    enum class NodeKind {
        CLASS, FUNCTION, FIELD, IMPORT_BLOCK, REGION
    }

    data class ScopeNode(
        val kind: NodeKind,
        val name: String,
        val indent: Int,
        /** byte offset of the start of the definition line */
        val startOffset: Int,
        /** byte offset of the character just past the last line that belongs to this scope */
        var endOffset: Int,
        /** children nested under this node */
        val children: MutableList<ScopeNode> = mutableListOf()
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the tree of top-level [ScopeNode]s (children nested). */
    fun buildTree(text: CharSequence): List<ScopeNode> {
        val lines = splitLines(text)
        val flat = buildFlat(lines, text)
        return nestByIndent(flat)
    }

    /** Returns the same nodes as [buildTree] but in document order (flat list). */
    fun buildFlat(text: CharSequence): List<ScopeNode> {
        val lines = splitLines(text)
        return buildFlat(lines, text)
    }

    // -------------------------------------------------------------------------
    // Line-level parsing
    // -------------------------------------------------------------------------

    internal data class LineInfo(
        val indent: Int,
        val startOffset: Int,
        val endOffset: Int,   // exclusive, does NOT include newline char(s)
        val lineEndOffset: Int // exclusive, includes newline char(s)
    )

    private fun splitLines(text: CharSequence): List<LineInfo> {
        val result = mutableListOf<LineInfo>()
        var i = 0
        val len = text.length
        while (i < len) {
            val lineStart = i
            // measure indent (spaces or tabs)
            var indent = 0
            while (i < len && (text[i] == ' ' || text[i] == '\t')) {
                indent += if (text[i] == '\t') 4 else 1
                i++
            }
            val contentStart = i
            // advance to end of line
            while (i < len && text[i] != '\n' && text[i] != '\r') i++
            val lineEnd = i
            // skip newline chars
            if (i < len && text[i] == '\r') i++
            if (i < len && text[i] == '\n') i++
            result += LineInfo(indent, lineStart, lineEnd, i)
        }
        return result
    }

    private fun buildFlat(lines: List<LineInfo>, text: CharSequence): List<ScopeNode> {
        val nodes = mutableListOf<ScopeNode>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val content = text.substring(line.startOffset + indentChars(text, line.startOffset), line.endOffset).trimEnd()

            when {
                // --- import block ---
                content.startsWith("import ") || content.startsWith("from ") -> {
                    val blockStart = line.startOffset
                    val blockIndent = line.indent
                    var j = i + 1
                    while (j < lines.size) {
                        val nc = text.substring(
                            lines[j].startOffset + indentChars(text, lines[j].startOffset),
                            lines[j].endOffset
                        ).trimEnd()
                        if (nc.startsWith("import ") || nc.startsWith("from ")) j++
                        else break
                    }
                    val blockEnd = lines[j - 1].lineEndOffset
                    nodes += ScopeNode(
                        NodeKind.IMPORT_BLOCK, "imports",
                        blockIndent, blockStart, blockEnd
                    )
                    i = j
                    continue
                }

                // --- region ---
                content.startsWith("# region") -> {
                    val label = content.removePrefix("# region").trim().ifEmpty { "region" }
                    nodes += ScopeNode(NodeKind.REGION, label, line.indent, line.startOffset, line.lineEndOffset)
                }

                // --- class variants ---
                isClassHeader(content) -> {
                    val name = extractName(content, extractClassKeywordLength(content))
                    val bodyEnd = findBodyEnd(lines, i, line.indent, text)
                    nodes += ScopeNode(NodeKind.CLASS, name, line.indent, line.startOffset, bodyEnd)
                }

                // --- function / async def ---
                isFunctionHeader(content) -> {
                    val kwLen = if (content.startsWith("async ")) "async def ".length else "def ".length
                    val name = extractName(content, kwLen)
                    val bodyEnd = findBodyEnd(lines, i, line.indent, text)
                    nodes += ScopeNode(NodeKind.FUNCTION, name, line.indent, line.startOffset, bodyEnd)
                }

                // --- top-level assignment / annotation ---
                line.indent == 0 && isTopLevelAssignment(content) -> {
                    val name = content.substringBefore('=').substringBefore(':').trim()
                    nodes += ScopeNode(NodeKind.FIELD, name, 0, line.startOffset, line.lineEndOffset)
                }
            }
            i++
        }
        return nodes
    }

    // -------------------------------------------------------------------------
    // Nesting
    // -------------------------------------------------------------------------

    private fun nestByIndent(flat: List<ScopeNode>): List<ScopeNode> {
        val roots = mutableListOf<ScopeNode>()
        val stack = mutableListOf<ScopeNode>()
        for (node in flat) {
            if (node.kind == NodeKind.FIELD || node.kind == NodeKind.IMPORT_BLOCK) {
                // These stay flat at whatever level they are; find parent by indent
                val parent = stack.lastOrNull { it.indent < node.indent }
                if (parent != null) parent.children += node else roots += node
                continue
            }
            // Pop stack entries that are not ancestors
            while (stack.isNotEmpty() && stack.last().indent >= node.indent) stack.removeLast()
            val parent = stack.lastOrNull()
            if (parent != null) parent.children += node else roots += node
            if (node.kind == NodeKind.CLASS || node.kind == NodeKind.FUNCTION) stack += node
        }
        return roots
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun indentChars(text: CharSequence, offset: Int): Int {
        var i = offset
        val len = text.length
        while (i < len && (text[i] == ' ' || text[i] == '\t')) i++
        return i - offset
    }

    private fun isClassHeader(content: String): Boolean {
        return content.startsWith("class ") ||
            content.startsWith("class def ") ||
            content.startsWith("data class ") ||
            content.startsWith("frozen data class ") ||
            content.startsWith("enum class ") ||
            content.startsWith("protocol ") ||
            // with modifiers: public/private/abstract/final class ...
            modifierPrefixedClass(content)
    }

    private fun modifierPrefixedClass(content: String): Boolean {
        val modifiers = listOf("public", "private", "abstract", "final", "static")
        for (mod in modifiers) {
            if (content.startsWith("$mod ")) {
                val rest = content.removePrefix("$mod ").trimStart()
                if (isClassHeader(rest)) return true
            }
        }
        return false
    }

    private fun isFunctionHeader(content: String): Boolean {
        if (content.startsWith("def ") || content.startsWith("async def ")) return true
        // with modifiers
        val modifiers = listOf("public", "private", "abstract", "final", "static", "override")
        for (mod in modifiers) {
            if (content.startsWith("$mod ")) {
                val rest = content.removePrefix("$mod ").trimStart()
                if (isFunctionHeader(rest)) return true
            }
        }
        return false
    }

    private fun extractClassKeywordLength(content: String): Int {
        return when {
            content.startsWith("frozen data class ") -> "frozen data class ".length
            content.startsWith("data class ") -> "data class ".length
            content.startsWith("enum class ") -> "enum class ".length
            content.startsWith("class def ") -> "class def ".length
            content.startsWith("class ") -> "class ".length
            content.startsWith("protocol ") -> "protocol ".length
            else -> {
                // strip modifier(s) then recurse
                val modifiers = listOf("public", "private", "abstract", "final", "static")
                for (mod in modifiers) {
                    if (content.startsWith("$mod ")) {
                        val rest = content.removePrefix("$mod ").trimStart()
                        return "$mod ".length + extractClassKeywordLength(rest)
                    }
                }
                0
            }
        }
    }

    private fun extractName(content: String, keywordLen: Int): String {
        val after = content.drop(keywordLen)
        // name goes up to first '(', ':', space
        return after.takeWhile { it.isLetterOrDigit() || it == '_' }.trim()
    }

    private fun isTopLevelAssignment(content: String): Boolean {
        if (content.isBlank() || content.startsWith("#")) return false
        if (isClassHeader(content) || isFunctionHeader(content)) return false
        if (content.startsWith("import ") || content.startsWith("from ")) return false
        val nameEnd = content.indexOfFirst { !it.isLetterOrDigit() && it != '_' }
        if (nameEnd <= 0) return false
        val after = content.substring(nameEnd).trimStart()
        return after.startsWith("=") || after.startsWith(":")
    }

    /**
     * Given the index of a header line, find the offset just past the last line
     * of the indented body that belongs to it.
     */
    internal fun findBodyEnd(lines: List<LineInfo>, headerIdx: Int, headerIndent: Int, text: CharSequence): Int {
        var lastBodyLineEnd = lines[headerIdx].lineEndOffset
        for (j in headerIdx + 1 until lines.size) {
            val l = lines[j]
            val contentStart = l.startOffset + indentChars(text, l.startOffset)
            val isBlank = contentStart >= l.endOffset  // empty / whitespace-only
            if (isBlank) {
                // blank lines are part of the body if followed by more indented lines
                continue
            }
            if (l.indent > headerIndent) {
                lastBodyLineEnd = l.lineEndOffset
            } else {
                break
            }
        }
        return lastBodyLineEnd
    }

}
