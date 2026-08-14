package dev.basedpython.pycharm.run.main

/**
 * How a parameter can be handed to `main` itself.
 *
 * Mirrors the `kind` field of the spec basedpython emits from the signature: a positional-only
 * parameter is always passed positionally even when the command line named it with `--`, a
 * keyword-only one takes no positional slot at all, and everything else can go either way.
 */
internal enum class ByParameterKind { POSITIONAL, ANY, KEYWORD }

/**
 * The command-line spelling of an annotation.
 *
 * Matched on the annotation *as written*, because that is what basedpython does: the converter it
 * emits is the same name the source used, so an alias or a locally rebound `int` is not the `int`
 * this list means. Anything not here is simply not exposed on the command line.
 */
internal enum class ByCliType {
    STR,
    INT,
    FLOAT,
    /** A flag pair — `--verbose` / `--no-verbose` — rather than a value, and it takes no positional slot. */
    BOOL,
    PATH,
    ;

    companion object {
        fun of(annotation: String): ByCliType? = when (annotation.trim()) {
            "str" -> STR
            "int" -> INT
            "float" -> FLOAT
            "bool" -> BOOL
            "Path", "pathlib.Path" -> PATH
            else -> null
        }
    }
}

/** One parameter of `main`, as the command line sees it. */
internal data class ByMainParameter(
    val name: String,
    /** The annotation as written, or `""` when the parameter has none. */
    val annotation: String,
    /** The default as written, or null when the parameter is required. */
    val default: String?,
    val kind: ByParameterKind,
) {
    val type: ByCliType? get() = ByCliType.of(annotation)
    val isRequired: Boolean get() = default == null
    val isExposed: Boolean get() = type != null

    /**
     * Both spellings argparse registers for this parameter, dash form first.
     *
     * An underscore in a name is also accepted as written, so `out_dir` answers to `--out-dir` and
     * `--out_dir` alike; the dash form is the one to *write*, being the one the docs use.
     */
    val flags: List<String>
        get() = if ('_' in name) listOf("--${name.replace('_', '-')}", "--$name") else listOf("--$name")

    val flag: String get() = flags.first()

    /** The `--no-…` spelling that sets a [ByCliType.BOOL] parameter false. */
    val negativeFlag: String get() = "--no-${flag.removePrefix("--")}"
}

/**
 * A top-level `def main` and what running it would take.
 *
 * basedpython turns `main`'s parameters into the program's command-line interface and appends the
 * `__main__` guard that feeds them in, so this is everything needed to ask a user for the arguments
 * a run needs — and to know when there is nothing to ask.
 */
internal data class ByMainFunction(
    /** 0-based line of the `def`. */
    val line: Int,
    val isAsync: Boolean,
    /** Declared order. Variadics are dropped: they are never exposed and never require a value. */
    val parameters: List<ByMainParameter>,
    /** `main`'s docstring, which becomes the generated parser's `--help` description. */
    val docstring: String?,
) {
    /** The parameters the command line fills. */
    val exposed: List<ByMainParameter> get() = parameters.filter { it.isExposed }

    /** Those of [exposed] that have no default, so a run without them fails to start. */
    val required: List<ByMainParameter> get() = exposed.filter { it.isRequired }

    /**
     * The parameter that stops this `main` being an entry point at all, if any.
     *
     * A required parameter the command line cannot supply means calling `main` would raise
     * `TypeError`, so basedpython emits *no* guard — the module runs and quietly does nothing. That
     * silence is worth naming in the UI, because nothing else about the run reports it.
     */
    val blockedBy: ByMainParameter? get() = parameters.firstOrNull { it.isRequired && !it.isExposed }

    val isEntryPoint: Boolean get() = blockedBy == null

    /** True when there is an argument form worth showing. */
    val takesArguments: Boolean get() = isEntryPoint && exposed.isNotEmpty()
}

/**
 * Reads a top-level `main` out of `.by` source text.
 *
 * Textual, for the reason [dev.basedpython.pycharm.run.test.ByTestDeclarations] gives: the PSI for
 * `.by` is one leaf per token, with no declarations to walk. The rules it implements are
 * basedpython's own, from the `main_function` transpiler pass:
 *
 *  - only a *top-level* `def main` / `async def main` counts, and the last one wins, because that
 *    is the binding `main` resolves to once the module body has run
 *  - a `private main` is renamed, so it is not an entry point
 *  - a module that already invokes `main` — a hand-written guard or a bare top-level call — keeps
 *    its own entry point, and no argument parser is generated
 */
internal object ByMainSignature {

    /** The `main` declared on [line], or null when that line declares no top-level `main`. */
    fun at(lineText: (Int) -> String, lineCount: Int, line: Int): ByMainFunction? {
        val match = MAIN_DEF.find(lineText(line)) ?: return null
        val signature = signatureAt(lineText, lineCount, line) ?: return null
        return ByMainFunction(
            line = line,
            isAsync = match.groupValues[2].isNotBlank(),
            parameters = parameters(signature.text),
            docstring = docstring(lineText, lineCount, signature.endLine),
        )
    }

    /**
     * The `main` this file's entry point would be, or null when it has none.
     *
     * Searches backwards so the last definition wins, matching the transpiler.
     */
    fun find(lineText: (Int) -> String, lineCount: Int): ByMainFunction? {
        for (line in lineCount - 1 downTo 0) {
            at(lineText, lineCount, line)?.let { return it }
        }
        return null
    }

    /**
     * True when the module invokes `main` itself, so basedpython adds no guard and no argument
     * parser — the program's arguments are then whatever its own code makes of `sys.argv`.
     */
    fun invokesMain(lineText: (Int) -> String, lineCount: Int): Boolean =
        (0 until lineCount).any { line ->
            val text = lineText(line)
            MAIN_GUARD.matches(text) || BARE_MAIN_CALL.matches(text)
        }

    /** A `if __name__ == "__main__":` line, which is a run target in its own right. */
    val MAIN_GUARD: Regex = Regex("""^\s*if\s+__name__\s*==\s*(['"])__main__\1\s*:.*$""")

    /**
     * A top-level `def main` / `async def main`, optionally carrying an `export` or `public`
     * modifier. `private` is deliberately absent: it renames the function, which stops it being an
     * entry point.
     */
    val MAIN_DEF: Regex = Regex("""^((?:export|public)\s+)?(async\s+)?def\s+main\s*\(""")

    /** A top-level `main(…)` call, which counts as the module invoking its own entry point. */
    private val BARE_MAIN_CALL = Regex("""^main\s*\(.*$""")

    private data class Signature(val text: String, val endLine: Int)

    /**
     * The text between `main`'s parentheses, and the line the closing one sits on.
     *
     * Scanned character by character across lines rather than matched, because a signature is
     * routinely wrapped over several of them and may hold strings, comments and nested brackets —
     * `def main(name: str = "a, b", *, out: Path = Path("."))` has three commas that are not
     * separators.
     */
    private fun signatureAt(lineText: (Int) -> String, lineCount: Int, line: Int): Signature? {
        val scanner = ByTextScanner()
        val text = StringBuilder()
        var depth = 0
        var started = false
        for (index in line until lineCount) {
            val current = lineText(index)
            for (char in current) {
                if (scanner.isCode(char)) {
                    if (char in OPENERS) {
                        depth++
                        if (depth == 1 && !started) {
                            started = true
                            continue
                        }
                    } else if (char in CLOSERS) {
                        depth--
                        if (depth == 0 && started) return Signature(text.toString(), index)
                    }
                }
                if (started) text.append(char)
            }
            if (!started && index > line) return null
            // A wrapped signature joins on a space: the newline itself is not part of any token.
            if (started) text.append(' ')
            scanner.endLine()
        }
        return null
    }

    /** Splits a parameter list on its top-level commas and reads each entry. */
    private fun parameters(signature: String): List<ByMainParameter> {
        var kind = ByParameterKind.ANY
        val parameters = mutableListOf<ByMainParameter>()
        for (entry in splitTopLevel(signature, ',')) {
            val text = entry.trim()
            if (text.isEmpty()) continue
            // `/` closes the positional-only group behind it; `*` opens the keyword-only one ahead.
            if (text == "/") {
                parameters.replaceAll { it.copy(kind = ByParameterKind.POSITIONAL) }
                continue
            }
            // A bare `*` opens the keyword-only group, and so does `*args`; neither is itself a
            // parameter the command line fills — variadics are never exposed and never block a
            // guard, so they are dropped rather than reported.
            if (text.startsWith("*")) {
                kind = ByParameterKind.KEYWORD
                continue
            }
            parameters += parameter(text, kind)
        }
        return parameters
    }

    private fun parameter(text: String, kind: ByParameterKind): ByMainParameter {
        val equals = indexOfTopLevel(text, '=')
        val head = if (equals < 0) text else text.substring(0, equals)
        val default = if (equals < 0) null else text.substring(equals + 1).trim()
        val colon = indexOfTopLevel(head, ':')
        return ByMainParameter(
            name = (if (colon < 0) head else head.substring(0, colon)).trim(),
            annotation = if (colon < 0) "" else head.substring(colon + 1).trim(),
            default = default,
            kind = kind,
        )
    }

    /**
     * The docstring opening on the first non-blank line after [defEndLine], as plain text.
     *
     * Only the literal's own text is wanted, so the quotes go and a triple-quoted block is joined
     * back into paragraphs by the caller; a docstring that runs past the end of the file is read to
     * whatever it reached.
     */
    private fun docstring(lineText: (Int) -> String, lineCount: Int, defEndLine: Int): String? {
        val start = (defEndLine + 1 until lineCount).firstOrNull { lineText(it).isNotBlank() } ?: return null
        val first = lineText(start).trim()
        val quote = QUOTES.firstOrNull { first.startsWith(it) } ?: return null
        val body = StringBuilder(first.removePrefix(quote))
        val closed = body.indexOf(quote)
        if (closed >= 0) return body.substring(0, closed).trim().ifBlank { null }
        for (index in start + 1 until lineCount) {
            val line = lineText(index)
            val end = line.indexOf(quote)
            if (end >= 0) {
                body.append('\n').append(line.substring(0, end))
                break
            }
            body.append('\n').append(line)
        }
        return body.toString().trim().ifBlank { null }
    }

    private val QUOTES = listOf("\"\"\"", "'''", "\"", "'")
    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"
}

/**
 * Tracks whether the character being read is code, rather than the inside of a string or a comment.
 *
 * The one state a line-oriented reader of `.by` needs, and enough of it: a triple-quoted string
 * survives to the next line, a `#` comment does not, and a backslash escapes the next character.
 */
private class ByTextScanner {
    private var quote: String? = null
    private var inComment = false
    private var escaped = false
    private var pending = StringBuilder()

    fun endLine() {
        inComment = false
        escaped = false
        if (quote != null && quote!!.length == 1) quote = null
    }

    /** Consumes [char] and reports whether it is code the caller should act on. */
    fun isCode(char: Char): Boolean {
        if (inComment) return false
        if (escaped) {
            escaped = false
            return false
        }
        val open = quote
        if (open != null) {
            pending.append(char)
            if (char == '\\' && open.length == 1) escaped = true
            if (pending.length >= open.length && pending.endsWith(open)) {
                quote = null
                pending = StringBuilder()
            }
            return false
        }
        if (char == '#') {
            inComment = true
            return false
        }
        if (char == '"' || char == '\'') {
            // Every quote is read as a single-quoted one, so `"""text"""` is seen as an empty
            // string, then `text` inside the third and fourth quotes, then another empty one. The
            // content still never counts as code, which is all this is asked to get right.
            quote = char.toString()
            pending = StringBuilder()
            return false
        }
        return true
    }
}

/** Splits [text] on every [separator] that is not inside brackets, a string, or a comment. */
private fun splitTopLevel(text: String, separator: Char): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    val scanner = ByTextScanner()
    var depth = 0
    for (char in text) {
        if (scanner.isCode(char)) {
            when (char) {
                in "([{" -> depth++
                in ")]}" -> depth--
                separator -> if (depth == 0) {
                    parts += current.toString()
                    current.clear()
                    continue
                }
            }
        }
        current.append(char)
    }
    parts += current.toString()
    return parts
}

/** The first [needle] in [text] that is not inside brackets, a string, or a comment; -1 for none. */
private fun indexOfTopLevel(text: String, needle: Char): Int {
    val scanner = ByTextScanner()
    var depth = 0
    for ((index, char) in text.withIndex()) {
        if (!scanner.isCode(char)) continue
        when (char) {
            in "([{" -> depth++
            in ")]}" -> depth--
            needle -> if (depth == 0) return index
        }
    }
    return -1
}
