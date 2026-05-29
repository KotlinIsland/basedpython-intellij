package dev.basedpython.pycharm.refactoring

/**
 * Pure, side-effect-free helpers backing the [InlineVariableAction] refactoring.
 *
 * Inlining a local variable in a `.by` file means:
 *  1. find the *single* assignment line `name = expr` for the identifier under the caret,
 *  2. replace each later word-boundary usage of `name` with `(expr)` (parenthesized to
 *     preserve operator precedence), then
 *  3. delete the assignment line entirely.
 *
 * The plugin's PSI is coarse (blocks/statements, not a full expression tree), so this is a
 * deliberately heuristic, text-based refactoring driven purely by the raw document text plus the
 * caret offset. All of the logic that decides *what* to delete and *where* to substitute lives
 * here so it can be unit-tested without an IDE fixture; [InlineVariableAction] is a thin wrapper
 * that applies the resulting [InlinePlan] under a write command.
 */
object InlineLogic {

    /**
     * A single text replacement: substitute the half-open range `[start, end)` with [replacement].
     */
    data class Edit(val start: Int, val end: Int, val replacement: String)

    /**
     * The result of planning an inline.
     *
     * @param name the identifier being inlined
     * @param exprText the (parenthesized) replacement text substituted at each usage
     * @param deleteStart start offset of the assignment line to delete (inclusive)
     * @param deleteEnd end offset of the assignment line to delete (exclusive, includes trailing `\n`)
     * @param usageReplacements word-boundary occurrence ranges to replace, sorted ascending,
     *   none of which overlap the deleted assignment line
     */
    data class InlinePlan(
        val name: String,
        val exprText: String,
        val deleteStart: Int,
        val deleteEnd: Int,
        val usageReplacements: List<IntRange>,
    ) {
        /**
         * Flattens the plan into a list of [Edit]s sorted by descending start offset, so callers
         * may apply them in order without invalidating earlier offsets.
         */
        fun toEdits(): List<Edit> {
            val edits = ArrayList<Edit>(usageReplacements.size + 1)
            for (r in usageReplacements) {
                edits.add(Edit(r.first, r.last + 1, exprText))
            }
            edits.add(Edit(deleteStart, deleteEnd, ""))
            edits.sortByDescending { it.start }
            return edits
        }
    }

    // ------------------------------------------------------------------
    // Identifier under caret
    // ------------------------------------------------------------------

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Returns the identifier word that the caret at [offset] sits on or directly adjacent to, or
     * `null` if the caret is not on an identifier. The caret is considered "on" an identifier when
     * it is inside the word or touching either edge.
     */
    fun identifierAt(text: CharSequence, offset: Int): String? {
        if (text.isEmpty()) return null
        val len = text.length
        var pos = offset.coerceIn(0, len)
        // If the char to the right is not an ident char, try the char to the left so a caret at
        // the trailing edge of a word still resolves.
        if (pos >= len || !isIdentChar(text[pos])) {
            if (pos > 0 && isIdentChar(text[pos - 1])) pos-- else return null
        }
        var start = pos
        while (start > 0 && isIdentChar(text[start - 1])) start--
        var end = pos
        while (end < len && isIdentChar(text[end])) end++
        val word = text.substring(start, end)
        // An identifier cannot begin with a digit.
        if (word.isEmpty() || word[0].isDigit()) return null
        return word
    }

    // ------------------------------------------------------------------
    // Line helpers (kept local so this file does not depend on ExtractionLogic)
    // ------------------------------------------------------------------

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
    // Assignment scanning
    // ------------------------------------------------------------------

    /**
     * Describes one `name = expr` assignment found in the text.
     *
     * @param lineStart offset of the start of the assignment line (column 0)
     * @param lineEndInclusive offset just past the line, including any trailing `\n`
     * @param eqOffset offset of the `=` sign
     * @param rhsStart offset of the first non-space char of the RHS
     * @param rhsEnd offset just past the last char of the RHS (before any trailing newline)
     */
    private data class Assignment(
        val lineStart: Int,
        val lineEndInclusive: Int,
        val eqOffset: Int,
        val rhsStart: Int,
        val rhsEnd: Int,
    )

    /**
     * A bare-bones assignment matcher: a line of the form `<indent>name <op>= <rhs>` where the only
     * permitted assignment operator is a single `=` (augmented assignments like `+=` are *not*
     * inline targets and are ignored, as are `==` comparisons). Returns `null` if [line] is not a
     * simple assignment to [name].
     */
    private fun matchAssignment(text: CharSequence, name: String, lineStart: Int): Assignment? {
        val lineEnd = lineEndOffsetInclusive(text, lineStart)
        // content end excludes a trailing newline
        var contentEnd = lineEnd
        if (contentEnd > lineStart && text[contentEnd - 1] == '\n') contentEnd--

        // skip indentation
        var i = lineStart
        while (i < contentEnd && (text[i] == ' ' || text[i] == '\t')) i++
        val identStart = i
        while (i < contentEnd && isIdentChar(text[i])) i++
        val ident = text.substring(identStart, i)
        if (ident != name) return null

        // skip spaces before operator
        var j = i
        while (j < contentEnd && (text[j] == ' ' || text[j] == '\t')) j++
        if (j >= contentEnd || text[j] != '=') return null
        // reject '==' (comparison) and augmented ops would have been caught above as ident!=name
        if (j + 1 < contentEnd && text[j + 1] == '=') return null
        val eqOffset = j

        // RHS begins after the '=' and following spaces
        var r = eqOffset + 1
        while (r < contentEnd && (text[r] == ' ' || text[r] == '\t')) r++
        val rhsStart = r
        var rhsEnd = contentEnd
        // trim trailing spaces from RHS
        while (rhsEnd > rhsStart && (text[rhsEnd - 1] == ' ' || text[rhsEnd - 1] == '\t')) rhsEnd--
        if (rhsEnd <= rhsStart) return null // empty RHS

        return Assignment(lineStart, lineEnd, eqOffset, rhsStart, rhsEnd)
    }

    /** Finds every simple assignment to [name] across the whole document. */
    private fun findAssignments(text: CharSequence, name: String): List<Assignment> {
        val result = ArrayList<Assignment>()
        var lineStart = 0
        val len = text.length
        while (lineStart <= len) {
            matchAssignment(text, name, lineStart)?.let { result.add(it) }
            val lineEnd = lineEndOffsetInclusive(text, lineStart)
            if (lineEnd <= lineStart) break // no progress (EOF with no newline)
            lineStart = lineEnd
            if (lineStart == len) break
        }
        return result
    }

    // ------------------------------------------------------------------
    // Word-boundary occurrence scanning
    // ------------------------------------------------------------------

    /**
     * Returns the start offsets of every word-boundary occurrence of [name] in [text]. A match is a
     * run of identifier chars exactly equal to [name] that is not preceded or followed by another
     * identifier char (so `name` does not match inside `names` or `myname`).
     */
    fun wordOccurrences(text: CharSequence, name: String): List<Int> {
        if (name.isEmpty()) return emptyList()
        val result = ArrayList<Int>()
        val len = text.length
        var i = 0
        while (i < len) {
            if (!isIdentChar(text[i])) {
                i++
                continue
            }
            // start of an identifier run
            val start = i
            var j = i
            while (j < len && isIdentChar(text[j])) j++
            if (text.substring(start, j) == name) result.add(start)
            i = j
        }
        return result
    }

    // ------------------------------------------------------------------
    // Plan builder
    // ------------------------------------------------------------------

    /**
     * Plans an inline of the identifier under [caretOffset]. Returns `null` (a no-op) when:
     *  - the caret is not on a valid identifier,
     *  - there is not exactly one simple `name = expr` assignment to that identifier,
     *  - the RHS is empty,
     *  - there are no usages of the variable outside its own assignment, or
     *  - the assignment's RHS spans multiple lines (we never see that here since [matchAssignment]
     *    is single-line, but a defensive check is included).
     *
     * Each surviving usage is replaced with the parenthesized RHS, e.g. `a + b` → `(a + b)`.
     * Occurrences that fall on the assignment's LHS identifier are skipped.
     */
    fun planInline(text: CharSequence, caretOffset: Int): InlinePlan? {
        val name = identifierAt(text, caretOffset) ?: return null
        if (!isValidIdentifier(name)) return null
        return planInlineFor(text, name)
    }

    /** As [planInline] but the target identifier is supplied directly. */
    fun planInlineFor(text: CharSequence, name: String): InlinePlan? {
        if (!isValidIdentifier(name)) return null
        val assignments = findAssignments(text, name)
        if (assignments.size != 1) return null
        val a = assignments.single()

        val rhs = text.substring(a.rhsStart, a.rhsEnd)
        if (rhs.isBlank()) return null
        if (rhs.contains('\n')) return null

        val exprText = parenthesize(rhs)

        // The LHS identifier sits between lineStart and eqOffset; skip any occurrence whose start
        // falls in that range so we never substitute the variable being defined.
        val lhsIdentStart = run {
            var i = a.lineStart
            while (i < a.eqOffset && (text[i] == ' ' || text[i] == '\t')) i++
            i
        }

        val replacements = ArrayList<IntRange>()
        for (start in wordOccurrences(text, name)) {
            // skip the defining occurrence on the LHS
            if (start == lhsIdentStart) continue
            // skip any occurrence inside the assignment line's LHS region defensively
            if (start in a.lineStart until a.eqOffset) continue
            replacements.add(start until (start + name.length))
        }

        if (replacements.isEmpty()) return null

        return InlinePlan(
            name = name,
            exprText = exprText,
            deleteStart = a.lineStart,
            deleteEnd = a.lineEndInclusive,
            usageReplacements = replacements.sortedBy { it.first },
        )
    }

    // ------------------------------------------------------------------
    // Expression parenthesization
    // ------------------------------------------------------------------

    /**
     * Wraps [expr] in parentheses to preserve precedence when substituted, unless it is "atomic":
     * a bare identifier, number, string/quoted literal, or an expression that is already fully
     * wrapped in a single pair of balanced parentheses or brackets. Trims surrounding whitespace.
     */
    fun parenthesize(expr: String): String {
        val e = expr.trim()
        if (e.isEmpty()) return "()"
        if (isAtomic(e)) return e
        return "($e)"
    }

    /** True if [e] needs no extra parentheses around it. */
    private fun isAtomic(e: String): Boolean {
        // a single identifier or dotted/indexed primary with no top-level operators
        if (isSimplePrimary(e)) return true
        // a numeric literal (int/float, optional leading sign already excluded by primary check)
        if (e.all { it.isDigit() || it == '.' } && e.any { it.isDigit() }) return true
        // already fully wrapped in one balanced pair of () or [] or {} or quotes
        if (isFullyWrapped(e)) return true
        return false
    }

    /** True for `foo`, `foo.bar`, `foo.bar.baz` — identifier chars and dots only. */
    private fun isSimplePrimary(e: String): Boolean {
        if (e.isEmpty()) return false
        if (!(e[0].isLetter() || e[0] == '_')) return false
        return e.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }

    /**
     * True if [e] is entirely enclosed by a single matching bracket pair `()`, `[]`, `{}` or a
     * matching pair of quotes, with the opener at index 0 and the closer at the last index.
     */
    private fun isFullyWrapped(e: String): Boolean {
        if (e.length < 2) return false
        val first = e[0]
        val last = e[e.length - 1]
        // quotes
        if ((first == '"' || first == '\'') && last == first) {
            // ensure no unescaped closing quote in the middle
            var i = 1
            while (i < e.length - 1) {
                if (e[i] == '\\') { i += 2; continue }
                if (e[i] == first) return false
                i++
            }
            return true
        }
        val close = when (first) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            else -> return false
        }
        if (last != close) return false
        // ensure the opening bracket is closed exactly at the end (single enclosing pair)
        var depth = 0
        for ((idx, c) in e.withIndex()) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0 && idx != e.length - 1) return false
                }
            }
        }
        return depth == 0
    }

    // ------------------------------------------------------------------
    // Identifier validation (mirrors ExtractionLogic.isValidIdentifier)
    // ------------------------------------------------------------------

    /** True if [name] is a syntactically valid identifier (letter/underscore start, then word chars). */
    fun isValidIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }
}
