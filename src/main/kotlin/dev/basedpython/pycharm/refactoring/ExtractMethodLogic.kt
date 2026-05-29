package dev.basedpython.pycharm.refactoring

import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan

/**
 * Pure, side-effect-free helpers backing the [ExtractMethodAction] refactoring for `.by` files.
 *
 * "Extract Method" takes a selection of one or more whole statements/lines, moves them into a
 * freshly-generated `def <name>():` block, and replaces the original lines with a call to that
 * new function. The new `def` is inserted directly above the *enclosing* function (the nearest
 * `def`/`async def` whose body contains the selection); if the selection is not inside any
 * function it is inserted at module level (after the leading comment/import header, mirroring
 * [ExtractionLogic.constantInsertionOffset]).
 *
 * As with the sibling refactorings the plugin has no semantic resolver, so this is a purely
 * text/indentation-driven, best-effort transformation:
 *
 *  - **No data-flow analysis.** The generated function is always zero-argument and the call site
 *    never receives arguments. Parameters/return values that *should* be threaded through are NOT
 *    inferred. An optional, conservative trailing-`return` heuristic (see [planExtractMethod]'s
 *    `addReturnHeuristic`) emits a `return <lhs>` for a single trailing simple assignment and
 *    rebinds the call site to `<lhs> = <name>()`, but this is opt-in and intentionally narrow.
 *  - **Indentation, not parsing, defines scope.** The enclosing `def` is found by scanning
 *    upward for a `def`/`async def` line whose indentation is strictly less than the selected
 *    block's indentation. This is heuristic and can be fooled by unusual layouts.
 *
 * All of the logic that decides *where* to insert text and *what* text to insert lives here so it
 * can be unit-tested without an IDE fixture; [ExtractMethodAction] is a thin wrapper that applies
 * the resulting plan under a write command.
 */
object ExtractMethodLogic {

    /** Indentation unit used for the body of the generated function (one level deeper). */
    const val INDENT_UNIT: String = "    "

    /**
     * The result of planning an extract-method.
     *
     * @param ok `false` for a no-op plan (e.g. empty/blank selection); the offsets are unspecified
     *   when `ok` is false and callers must not apply the plan.
     * @param name the generated function's name
     * @param insertOffset document offset at which [insertText] is inserted (always a line start)
     * @param insertText the full generated `def` block (already terminated with `\n`)
     * @param replaceStart start offset of the (line-expanded) selection to replace
     * @param replaceEnd end offset (exclusive) of the selection to replace, including trailing `\n`
     * @param replacementText the call line(s) that replace the selection (already terminated `\n`)
     */
    data class ExtractMethodPlan(
        val ok: Boolean,
        val name: String,
        val insertOffset: Int,
        val insertText: String,
        val replaceStart: Int,
        val replaceEnd: Int,
        val replacementText: String,
    ) {
        /** Adapts this plan to the shared [ExtractionPlan] applied by [AbstractExtractionAction]. */
        fun toExtractionPlan(): ExtractionPlan = ExtractionPlan(
            insertOffset = insertOffset,
            insertText = insertText,
            replaceStart = replaceStart,
            replaceEnd = replaceEnd,
            replaceWith = replacementText,
        )

        companion object {
            /** A sentinel no-op plan that callers should detect via [ok] and skip. */
            fun noOp(name: String): ExtractMethodPlan =
                ExtractMethodPlan(false, name, 0, "", 0, 0, "")
        }
    }

    // ------------------------------------------------------------------
    // Line helpers (kept local so this file does not depend on ExtractionLogic internals)
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

    /** The leading whitespace (spaces/tabs) of [line], as a literal prefix string. */
    fun leadingIndent(line: CharSequence): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i).toString()
    }

    /** Visual indentation width of [indent] (a tab counts as 4 columns). */
    fun indentWidth(indent: CharSequence): Int {
        var w = 0
        for (c in indent) w += if (c == '\t') 4 else 1
        return w
    }

    /** True if [line] (ignoring leading/trailing whitespace) has no content. */
    private fun isBlankLine(line: CharSequence): Boolean = line.isBlank()

    // ------------------------------------------------------------------
    // Selection → whole-line range
    // ------------------------------------------------------------------

    /**
     * Expands an arbitrary [selStart], [selEnd] selection to cover whole lines: the start snaps
     * back to its line start and the end snaps forward past the trailing newline of the line it
     * lands on. A zero-width selection collapses to the single line under the caret.
     *
     * The returned pair is `(lineStart, lineEndInclusive)` as document offsets.
     */
    fun expandToLines(text: CharSequence, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val len = text.length
        val s = selStart.coerceIn(0, len)
        val e = selEnd.coerceIn(0, len)
        val lo = minOf(s, e)
        var hi = maxOf(s, e)
        val start = lineStartOffset(text, lo)
        // If the selection end sits exactly at a line start (i.e. just after a newline) and the
        // selection is non-empty, the trailing line is not actually included — back up one char so
        // we end at the previous line's newline rather than swallowing the following line.
        if (hi > lo && hi > 0 && hi <= len && (hi == len || text[hi - 1] == '\n')) {
            if (hi > start) hi--
        }
        val end = lineEndOffsetInclusive(text, hi)
        return start to end
    }

    // ------------------------------------------------------------------
    // Indentation of a selected block
    // ------------------------------------------------------------------

    /**
     * Returns the common leading indentation shared by every non-blank line in [lines]. Blank
     * lines are ignored (they impose no constraint). When all lines are blank the result is "".
     * The common indent is computed by truncating to the shortest non-blank line's indent and then
     * verifying each non-blank line starts with it; if they diverge, the longest common prefix of
     * the indents is returned.
     */
    fun commonIndent(lines: List<String>): String {
        val indents = lines.filterNot { isBlankLine(it) }.map { leadingIndent(it) }
        if (indents.isEmpty()) return ""
        var prefix = indents[0]
        for (ind in indents.drop(1)) {
            prefix = commonPrefix(prefix, ind)
            if (prefix.isEmpty()) break
        }
        return prefix
    }

    private fun commonPrefix(a: String, b: String): String {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    // ------------------------------------------------------------------
    // Enclosing-def discovery
    // ------------------------------------------------------------------

    /**
     * Finds the enclosing function-definition line for a block that starts at offset [blockStart]
     * and is indented by [blockIndentWidth] columns. Scans upward from the line *above* the block
     * for the nearest `def`/`async def` whose own indentation is strictly less than
     * [blockIndentWidth]. Returns the offset of that def line's start, or `null` if the block is at
     * module level (no shallower enclosing def).
     */
    fun enclosingDefStart(text: CharSequence, blockStart: Int, blockIndentWidth: Int): Int? {
        var lineStart = blockStart
        while (lineStart > 0) {
            // step to the previous line
            val prevEnd = lineStart // current line start == prev line's end (inclusive of its \n)
            val prevStart = lineStartOffset(text, prevEnd - 1)
            val prevLine = text.substring(prevStart, prevEnd)
            val content = prevLine.trimEnd('\n', '\r')
            val trimmed = content.trim()
            if (trimmed.isNotEmpty()) {
                val ind = indentWidth(leadingIndent(content))
                if (ind < blockIndentWidth && isDefLine(trimmed)) {
                    return prevStart
                }
            }
            lineStart = prevStart
        }
        return null
    }

    /** True if [trimmedContent] begins a function definition (`def f(` / `async def f(`). */
    private fun isDefLine(trimmedContent: String): Boolean {
        val c = trimmedContent
        return c.startsWith("def ") || c.startsWith("def(") ||
            c.startsWith("async def ") || c.startsWith("async def(")
    }

    // ------------------------------------------------------------------
    // New function text
    // ------------------------------------------------------------------

    /**
     * Builds the generated function source. The selected [bodyLines] are de-indented by their
     * [common] indentation and then re-indented by [defIndent] + one [INDENT_UNIT], so relative
     * indentation within the block is preserved. Each emitted line is newline-terminated. Blank
     * lines in the body are emitted as empty lines (no trailing whitespace).
     *
     * @param defIndent the indentation of the generated `def` keyword itself (matches the
     *   enclosing scope; "" at module level)
     * @param trailingReturn optional `return <expr>` body suffix (already un-indented expression
     *   text) appended as the function's final statement
     */
    fun buildFunctionText(
        name: String,
        bodyLines: List<String>,
        common: String,
        defIndent: String,
        trailingReturn: String? = null,
    ): String {
        val bodyIndent = defIndent + INDENT_UNIT
        val sb = StringBuilder()
        sb.append(defIndent).append("def ").append(name).append("():\n")
        var emittedAny = false
        for (line in bodyLines) {
            val content = line.trimEnd('\n', '\r')
            if (isBlankLine(content)) {
                sb.append("\n")
                continue
            }
            val deIndented = if (content.startsWith(common)) content.substring(common.length) else content.trimStart()
            sb.append(bodyIndent).append(deIndented).append("\n")
            emittedAny = true
        }
        if (trailingReturn != null) {
            sb.append(bodyIndent).append("return ").append(trailingReturn).append("\n")
            emittedAny = true
        }
        // Guarantee a non-empty body so the generated function is syntactically valid.
        if (!emittedAny) {
            sb.append(bodyIndent).append("pass\n")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // Trailing-return heuristic
    // ------------------------------------------------------------------

    /**
     * If [lines]' last non-blank line is a simple assignment `<lhs> = <rhs>` to a single bare
     * identifier, returns that identifier; otherwise `null`. Used only when the opt-in
     * trailing-return heuristic is enabled. Augmented assignments (`+=`) and comparisons (`==`)
     * and tuple targets (`a, b = ...`) are deliberately rejected.
     */
    fun trailingAssignmentTarget(lines: List<String>): String? {
        val last = lines.lastOrNull { !isBlankLine(it) } ?: return null
        val content = last.trim()
        val eq = findSimpleAssignEq(content) ?: return null
        val lhs = content.substring(0, eq).trim()
        if (lhs.isEmpty()) return null
        if (lhs.any { it == ',' }) return null // tuple target
        if (!isValidIdentifier(lhs)) return null
        return lhs
    }

    /** Index of a top-level single `=` assignment operator in [content], or `null`. */
    private fun findSimpleAssignEq(content: String): Int? {
        var depth = 0
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
                '=' -> {
                    if (depth == 0) {
                        val prev = if (i > 0) content[i - 1] else ' '
                        val next = if (i + 1 < content.length) content[i + 1] else ' '
                        // reject ==, !=, <=, >=, +=, -=, *=, /=, %=, etc.
                        val augmented = prev in "+-*/%&|^<>!=~@"
                        if (next != '=' && !augmented) return i
                        // skip the second '=' of '=='
                        if (next == '=') i++
                    }
                }
            }
            i++
        }
        return null
    }

    // ------------------------------------------------------------------
    // Plan builder
    // ------------------------------------------------------------------

    /**
     * Plans an extract-method.
     *
     * Steps:
     *  1. Expand the selection to whole lines.
     *  2. Bail out ([ExtractMethodPlan.ok] == false) if the resulting block is empty or all-blank.
     *  3. Compute the block's common indentation.
     *  4. Locate the enclosing `def` (or fall back to module-level insertion via
     *     [ExtractionLogic.constantInsertionOffset]). The generated `def`'s own indentation equals
     *     the enclosing def's indentation, or "" at module level.
     *  5. Generate the function text and the replacement call line (a `<name>()` call at the
     *     block's original indentation).
     *
     * @param addReturnHeuristic when `true`, applies the conservative trailing-`return` heuristic
     *   (see [trailingAssignmentTarget]); the generated function returns the assigned identifier
     *   and the call site becomes `<lhs> = <name>()`.
     */
    fun planExtractMethod(
        text: CharSequence,
        selStart: Int,
        selEnd: Int,
        name: String,
        addReturnHeuristic: Boolean = false,
    ): ExtractMethodPlan {
        if (!isValidIdentifier(name)) return ExtractMethodPlan.noOp(name)

        val (blockStart, blockEnd) = expandToLines(text, selStart, selEnd)
        if (blockEnd <= blockStart) return ExtractMethodPlan.noOp(name)

        val block = text.substring(blockStart, blockEnd)
        val lines = splitKeepingStructure(block)
        if (lines.all { isBlankLine(it) }) return ExtractMethodPlan.noOp(name)

        val common = commonIndent(lines)
        val blockIndentWidth = indentWidth(common)

        val defStart = enclosingDefStart(text, blockStart, blockIndentWidth)
        val insertOffset: Int
        val defIndent: String
        if (defStart != null) {
            insertOffset = defStart
            defIndent = leadingIndent(text.substring(defStart, lineEndOffsetInclusive(text, defStart)).trimEnd('\n', '\r'))
        } else {
            insertOffset = ExtractionLogic.constantInsertionOffset(text)
            defIndent = ""
        }

        val returnTarget = if (addReturnHeuristic) trailingAssignmentTarget(lines) else null
        val functionText = buildFunctionText(name, lines, common, defIndent, returnTarget)
        // A blank line after the generated def keeps it visually separated from following code.
        val insertText = functionText + "\n"

        val callText = if (returnTarget != null) "$common$returnTarget = $name()\n" else "$common$name()\n"

        return ExtractMethodPlan(
            ok = true,
            name = name,
            insertOffset = insertOffset,
            insertText = insertText,
            replaceStart = blockStart,
            replaceEnd = blockEnd,
            replacementText = callText,
        )
    }

    /**
     * Splits a block into its constituent lines, dropping each line's own trailing newline. A
     * trailing empty element produced by a final `\n` is dropped so a block ending in a newline
     * yields exactly its visible lines.
     */
    fun splitKeepingStructure(block: String): List<String> {
        if (block.isEmpty()) return emptyList()
        val parts = block.split("\n")
        // split on the final "\n" produces a trailing "" we don't want as a body line
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    // ------------------------------------------------------------------
    // Name suggestion / validation
    // ------------------------------------------------------------------

    /** A reasonable default name for an extracted method. */
    fun defaultMethodName(): String = "extracted"

    /** True if [name] is a syntactically valid identifier (letter/underscore start, then word chars). */
    fun isValidIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        return name.all { it.isLetterOrDigit() || it == '_' }
    }
}
