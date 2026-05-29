package dev.basedpython.pycharm.refactoring

/**
 * Pure, side-effect-free helpers backing the [ExtractVariableAction] and
 * [IntroduceConstantAction] refactorings.
 *
 * The plugin's PSI is coarse (blocks/statements, not a full expression tree), so these
 * refactorings are driven entirely by the editor selection + raw document text. All the
 * logic that decides *where* to insert text and *what* text to insert lives here so it can
 * be unit-tested without an IDE fixture; the actions are thin wrappers that do the actual
 * (write-locked) document mutation.
 */
object ExtractionLogic {

    /**
     * The result of planning an extraction: a single insertion plus a single replacement.
     *
     * @param insertOffset document offset at which [insertText] should be inserted
     * @param insertText the full line(s) to insert (already terminated with `\n`)
     * @param replaceStart start offset of the selection to replace
     * @param replaceEnd end offset (exclusive) of the selection to replace
     * @param replaceWith the identifier that replaces the selected expression
     */
    data class ExtractionPlan(
        val insertOffset: Int,
        val insertText: String,
        val replaceStart: Int,
        val replaceEnd: Int,
        val replaceWith: String,
    )

    // ------------------------------------------------------------------
    // Indentation
    // ------------------------------------------------------------------

    /**
     * Returns the leading whitespace (spaces/tabs) of the line that [offset] falls on, as a
     * literal prefix string. Mixed tabs/spaces are preserved verbatim so the inserted line
     * matches the surrounding style exactly.
     */
    fun lineIndentString(text: CharSequence, offset: Int): String {
        val lineStart = lineStartOffset(text, offset)
        var i = lineStart
        val len = text.length
        while (i < len && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(lineStart, i)
    }

    /** Visual indentation width of the line containing [offset] (tab counts as 4). */
    fun lineIndentWidth(text: CharSequence, offset: Int): Int {
        val indent = lineIndentString(text, offset)
        var width = 0
        for (c in indent) width += if (c == '\t') 4 else 1
        return width
    }

    /** Offset of the first character of the line containing [offset]. */
    fun lineStartOffset(text: CharSequence, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = offset.coerceIn(0, text.length)
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    /** Offset just past the line containing [offset], including the trailing newline if present. */
    fun lineEndOffsetInclusive(text: CharSequence, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        val len = text.length
        while (i < len && text[i] != '\n') i++
        if (i < len && text[i] == '\n') i++
        return i
    }

    // ------------------------------------------------------------------
    // Replacement / insertion text
    // ------------------------------------------------------------------

    /** Builds an assignment line `name = expr`, prefixed with [indent] and terminated by `\n`. */
    fun buildAssignmentLine(indent: String, name: String, expr: String): String {
        val cleaned = expr.trim()
        return "$indent$name = $cleaned\n"
    }

    // ------------------------------------------------------------------
    // Constant insertion offset
    // ------------------------------------------------------------------

    /**
     * Computes the offset at which a module-level constant should be introduced: after any
     * leading run of comments, blank lines, and `import` / `from ... import` statements at the
     * top of the file. The returned offset is always the start of a line (or end of file).
     *
     * Examples:
     * - empty file → 0
     * - file starting with code → 0 (constant goes at the very top)
     * - file with imports → just after the last import line
     * - leading `#!`/`# coding` comments are treated as header and skipped
     */
    fun constantInsertionOffset(text: CharSequence): Int {
        val len = text.length
        if (len == 0) return 0
        var i = 0
        var lastHeaderEnd = 0
        while (i < len) {
            val lineStart = i
            // measure indent
            var j = i
            while (j < len && (text[j] == ' ' || text[j] == '\t')) j++
            // find end of line content + newline
            var k = j
            while (k < len && text[k] != '\n') k++
            val contentEnd = k
            val lineEndInclusive = if (k < len) k + 1 else k
            val content = text.substring(j, contentEnd).trimEnd()

            val isBlank = content.isEmpty()
            val isComment = content.startsWith("#")
            val isImport = content.startsWith("import ") ||
                content.startsWith("from ") ||
                content == "import" // tolerate bare keyword

            when {
                isBlank -> {
                    // blank line: tentatively part of header but don't advance the
                    // committed boundary (so we don't leave trailing blanks above code)
                    i = lineEndInclusive
                }
                isComment || isImport -> {
                    i = lineEndInclusive
                    lastHeaderEnd = i
                }
                else -> {
                    // first real statement: stop here
                    return if (lastHeaderEnd == 0) lineStart else lastHeaderEnd
                }
            }
        }
        // whole file was header (only comments/imports/blanks)
        return lastHeaderEnd
    }

    // ------------------------------------------------------------------
    // Plan builders
    // ------------------------------------------------------------------

    /**
     * Plans an Extract Variable: insert `name = <expr>` on its own line directly above the
     * statement that contains the selection, at that statement's indentation, and replace the
     * selection with `name`.
     */
    fun planExtractVariable(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan {
        val expr = text.substring(selectionStart, selectionEnd)
        val indent = lineIndentString(text, selectionStart)
        val insertOffset = lineStartOffset(text, selectionStart)
        val insertText = buildAssignmentLine(indent, name, expr)
        return ExtractionPlan(insertOffset, insertText, selectionStart, selectionEnd, name)
    }

    /**
     * Plans an Introduce Constant: insert `NAME = <expr>` at module top (after the import/comment
     * header) with no indentation, and replace the selection with `NAME`.
     *
     * If the constant is inserted in the middle of the file (header present, code follows) a
     * trailing blank line is added so the constant is visually separated from following code only
     * when it lands at offset 0 in a non-empty file.
     */
    fun planIntroduceConstant(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan {
        val expr = text.substring(selectionStart, selectionEnd)
        val insertOffset = constantInsertionOffset(text)
        var insertText = buildAssignmentLine("", name, expr)
        // If we're inserting at the very top of a file that has more content immediately
        // following (no blank separation), add a blank line for readability.
        val nextChar = if (insertOffset < text.length) text[insertOffset] else null
        if (insertOffset == 0 && nextChar != null && nextChar != '\n') {
            insertText += "\n"
        }
        return ExtractionPlan(insertOffset, insertText, selectionStart, selectionEnd, name)
    }

    // ------------------------------------------------------------------
    // Name suggestions / validation
    // ------------------------------------------------------------------

    /** A reasonable default identifier for an extracted variable. */
    fun defaultVariableName(): String = "extracted"

    /** Derives an UPPER_CASE constant name from a selected expression, falling back to a default. */
    fun defaultConstantName(expr: CharSequence): String {
        val ident = expr.trim().takeWhile { it.isLetterOrDigit() || it == '_' }.toString()
        val base = if (ident.isNotEmpty() && ident[0].isLetter()) ident else "CONSTANT"
        return toConstantCase(base)
    }

    /** Converts an identifier to UPPER_SNAKE_CASE (camelCase → CAMEL_CASE). */
    fun toConstantCase(name: String): String {
        if (name.isEmpty()) return "CONSTANT"
        val sb = StringBuilder()
        for ((idx, c) in name.withIndex()) {
            // Insert a separator at a camelCase boundary: an uppercase letter that
            // follows a lowercase letter or digit (so existing UPPER runs stay intact).
            if (c.isUpperCase() && idx > 0) {
                val prev = name[idx - 1]
                if (prev.isLowerCase() || prev.isDigit()) sb.append('_')
            }
            sb.append(c.uppercaseChar())
        }
        return sb.toString()
    }

    /** True if [name] is a syntactically valid identifier (letter/underscore start, then word chars). */
    fun isValidIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }
}
