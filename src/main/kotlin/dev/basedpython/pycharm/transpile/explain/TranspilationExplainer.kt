package dev.basedpython.pycharm.transpile.explain

/**
 * A single recognized basedpython-specific construct and how it maps to Python.
 *
 * @property constructName a short, stable identifier for the construct (e.g. `"null-safe access"`).
 * @property bySnippet the matched substring of the basedpython source (trimmed).
 * @property explanation a human-readable description of what the transpiler produces for it.
 * @property lineNumber 1-based line number in the basedpython source where the construct was found.
 */
data class TranspilationNote(
    val constructName: String,
    val bySnippet: String,
    val explanation: String,
    val lineNumber: Int,
)

/**
 * Pure, deterministic analyzer that explains what `by transpile` does to basedpython-specific
 * constructs.  It contains **no IntelliJ platform dependencies** so it can be exhaustively unit
 * tested in isolation.
 *
 * The analyzer scans the basedpython source line-by-line (regex based) and emits a
 * [TranspilationNote] for every recognized construct.  It is intentionally tolerant: unrecognized
 * lines simply produce no notes and never throw.  The transpiled Python text is accepted as a
 * second argument so callers can pass it through; the current implementation derives its
 * explanations purely from the basedpython side, which keeps results stable regardless of the
 * exact transpiler version.
 *
 * The framing in FEATURES.md §185 is an "AI-assist hook": this exposes the basedpython -> python
 * mapping in a structured form that an AI (or a human) can consume, implemented deterministically.
 */
object TranspilationExplainer {

    /** Names of every recognized construct, useful for documentation / tests. */
    val recognizedConstructs: List<String> = listOf(
        "null-safe access",
        "null-safe index",
        "elvis operator",
        "null-coalescing operator",
        "non-null assertion",
        "data-class modifier",
        "pattern match",
        "match case",
        "pipe operator",
        "string interpolation",
        "type modifier",
    )

    /**
     * Analyze [bySource] (the basedpython source) and optionally [pythonSource] (the transpiled
     * Python) and return the list of [TranspilationNote]s, in source order.
     *
     * Never throws on malformed input — the worst case is an empty result.
     */
    @JvmStatic
    @JvmOverloads
    fun explain(bySource: String?, pythonSource: String? = null): List<TranspilationNote> {
        if (bySource.isNullOrEmpty()) return emptyList()

        val notes = mutableListOf<TranspilationNote>()
        // Split on any newline flavour; keep 1-based line numbers.
        val lines = bySource.split("\n")
        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val line = rawLine.trimEnd('\r')
            val code = stripComment(line)
            if (code.isBlank()) continue
            analyzeLine(code, lineNumber, notes)
        }
        return notes
    }

    // ------------------------------------------------------------------
    // Per-construct recognizers
    // ------------------------------------------------------------------

    private fun analyzeLine(code: String, lineNumber: Int, out: MutableList<TranspilationNote>) {
        val snippet = code.trim()

        // --- non-null assertion `expr!!` ------------------------------------------------
        // Matched first so a trailing `!!` is not mistaken for anything else.
        if (NON_NULL.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "non-null assertion",
                bySnippet = snippet,
                explanation = "The `!!` non-null assertion transpiles to an unwrap that raises " +
                    "if the value is None (roughly `_assert_not_none(expr)`).",
                lineNumber = lineNumber,
            )
        }

        // --- null-coalescing `a ?? b` --------------------------------------------------
        if (NULL_COALESCE.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "null-coalescing operator",
                bySnippet = snippet,
                explanation = "The `??` null-coalescing operator becomes `a if a is not None else b`.",
                lineNumber = lineNumber,
            )
        } else if (ELVIS.containsMatchIn(code)) {
            // `?:` only counts when it is not part of `??` (handled above) and not a ternary.
            out += TranspilationNote(
                constructName = "elvis operator",
                bySnippet = snippet,
                explanation = "The `?:` elvis operator becomes `a if a is not None else b`.",
                lineNumber = lineNumber,
            )
        }

        // --- null-safe member access `a?.b` --------------------------------------------
        if (NULL_SAFE_ACCESS.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "null-safe access",
                bySnippet = snippet,
                explanation = "The `?.` null-safe access becomes a guarded access " +
                    "(`a.b if a is not None else None`).",
                lineNumber = lineNumber,
            )
        }

        // --- null-safe index `a?[i]` ---------------------------------------------------
        if (NULL_SAFE_INDEX.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "null-safe index",
                bySnippet = snippet,
                explanation = "The `?[` null-safe index becomes a guarded subscript " +
                    "(`a[i] if a is not None else None`).",
                lineNumber = lineNumber,
            )
        }

        // --- data-class modifier -------------------------------------------------------
        if (DATA_CLASS.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "data-class modifier",
                bySnippet = snippet,
                explanation = "A `data class` (or `@data`) becomes a Python `@dataclass`-decorated class.",
                lineNumber = lineNumber,
            )
        }

        // --- pattern match: `match expr:` ----------------------------------------------
        if (MATCH_HEADER.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "pattern match",
                bySnippet = snippet,
                explanation = "A `match` block transpiles to a Python 3.10+ structural `match` statement.",
                lineNumber = lineNumber,
            )
        }

        // --- match case: `case ...:` ---------------------------------------------------
        if (CASE_HEADER.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "match case",
                bySnippet = snippet,
                explanation = "A `case` clause becomes a Python `case` pattern inside the `match` statement.",
                lineNumber = lineNumber,
            )
        }

        // --- pipe operator `a |> f` ----------------------------------------------------
        if (PIPE.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "pipe operator",
                bySnippet = snippet,
                explanation = "The `|>` pipe operator becomes a nested call (`f(a)`).",
                lineNumber = lineNumber,
            )
        }

        // --- string interpolation (f-string) -------------------------------------------
        if (containsInterpolatedString(code)) {
            out += TranspilationNote(
                constructName = "string interpolation",
                bySnippet = snippet,
                explanation = "An interpolated string (`\"... \${expr} ...\"`) becomes a Python f-string.",
                lineNumber = lineNumber,
            )
        }

        // --- type modifiers `val`/`var`/`let`/`const` ----------------------------------
        if (TYPE_MODIFIER.containsMatchIn(code)) {
            out += TranspilationNote(
                constructName = "type modifier",
                bySnippet = snippet,
                explanation = "A `val`/`var`/`let`/`const` declaration becomes a plain Python " +
                    "assignment (the mutability keyword is erased).",
                lineNumber = lineNumber,
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Remove a trailing line comment (`# ...`) that is not inside a string literal.
     * Keeps `#` characters that appear inside single- or double-quoted strings.
     */
    internal fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> { i += 2; continue }
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** True if [code] contains a string literal with a `${...}` interpolation. */
    internal fun containsInterpolatedString(code: String): Boolean {
        // Require the interpolation to look like it sits inside a quote on this line.
        if (!code.contains("\${")) return false
        return INTERPOLATION_IN_STRING.containsMatchIn(code)
    }

    // ------------------------------------------------------------------
    // Patterns
    // ------------------------------------------------------------------

    // `expr!!` — `!!` not followed by `=` (avoid the (nonexistent) `!!=`), preceded by a word/paren/bracket.
    private val NON_NULL = Regex("""[\w\)\]]\s*!!(?!=)""")

    // `a ?? b`
    private val NULL_COALESCE = Regex("""\?\?""")

    // `a ?: b` — elvis. Not `??`. Allow optional spaces around `?:`.
    private val ELVIS = Regex("""[\w\)\]]\s*\?:""")

    // `a?.b` — null-safe member access. `?` immediately (optional ws) before `.identifier`.
    private val NULL_SAFE_ACCESS = Regex("""\?\s*\.\s*[A-Za-z_]""")

    // `a?[i]` — null-safe index.
    private val NULL_SAFE_INDEX = Regex("""\?\s*\[""")

    // `data class Foo` / `@data` / `@dataclass`
    private val DATA_CLASS = Regex("""(^|\s)(data\s+class\b|@data\b|@dataclass\b)""")

    // `match expr:` at the start of a (trimmed) statement.
    private val MATCH_HEADER = Regex("""^\s*match\b.*:\s*$""")

    // `case pattern:` at the start of a (trimmed) statement.
    private val CASE_HEADER = Regex("""^\s*case\b.*:\s*$""")

    // `a |> f`
    private val PIPE = Regex("""\|>""")

    // `${expr}` somewhere after an opening quote on the same line.
    private val INTERPOLATION_IN_STRING = Regex("""["'][^"']*\$\{""")

    // declaration keywords introducing a binding.
    private val TYPE_MODIFIER = Regex("""(^|\s)(val|var|let|const)\s+[A-Za-z_]\w*""")
}
