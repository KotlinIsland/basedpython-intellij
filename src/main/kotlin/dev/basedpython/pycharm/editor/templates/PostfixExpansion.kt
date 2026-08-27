package dev.basedpython.pycharm.editor.templates

/**
 * The document edit a postfix template performs, as pure data.
 *
 * Kept separate from the platform glue in [BasedPythonPostfixTemplateProvider] so the interesting
 * part — finding the expression the template applies to, and where the caret ends up — is testable
 * without an editor.
 *
 * @param startOffset first character replaced
 * @param endOffset one past the last character replaced
 * @param text what to put there
 * @param caretOffset absolute offset for the caret afterwards
 */
data class PostfixExpansion(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val caretOffset: Int,
)

/**
 * Marks where the caret goes in a template body. Stripped before the text reaches the document,
 * so it has to be a character no body would contain on its own.
 */
const val CARET_MARKER: String = "\u0000"

/**
 * Computes the edit for expanding a postfix template.
 *
 * [caretOffset] is the offset *after* the platform has already deleted the template key: by the time
 * `PostfixTemplate.expand` runs, `PostfixLiveTemplate.deleteTemplateKey` has removed `.print` (dot
 * included) from the document, so the caret sits immediately after the expression. Subtracting the
 * key length a second time — as this used to — walks back into the expression and replaces the wrong
 * range.
 *
 * @param body builds the replacement from the expression text. May contain newlines; every line
 *   after the first is indented to match the line the expression started on. May contain one
 *   [CARET_MARKER].
 * @return the edit, or null when there is no expression before the caret to apply the template to.
 */
fun postfixExpansion(text: CharSequence, caretOffset: Int, body: (String) -> String): PostfixExpansion? {
    if (caretOffset <= 0 || caretOffset > text.length) return null
    // The platform has already deleted the dot that triggered the template, so another dot right
    // before the caret means the dot it deleted was not an attribute access on a finished
    // expression: the user is mid-way through `...`, or through a `..` typo. No expression in
    // basedpython ends in a dot, so there is nothing here a template could apply to.
    if (text[caretOffset - 1] == '.') return null
    val start = expressionStart(text, caretOffset)
    if (start == null || start >= caretOffset) return null

    val expr = text.subSequence(start, caretOffset).toString()
    val indent = lineIndent(text, start)
    val rendered = body(expr).replace("\n", "\n$indent")

    val caretInBody = rendered.indexOf(CARET_MARKER)
    val stripped = if (caretInBody < 0) rendered else rendered.replace(CARET_MARKER, "")
    val caret = if (caretInBody < 0) start + stripped.length else start + caretInBody
    return PostfixExpansion(start, caretOffset, stripped, caret)
}

/** The leading whitespace of the line [offset] sits on. */
private fun lineIndent(text: CharSequence, offset: Int): String {
    var lineStart = offset
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
    val sb = StringBuilder()
    var i = lineStart
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
        sb.append(text[i])
        i++
    }
    return sb.toString()
}

/**
 * Scans backwards from [end] for the start of the expression the postfix template applies to.
 *
 * Walks over identifier characters, dots and digits, and skips whole bracketed groups and string
 * literals so `foo(a, b).len` and `"a b".len` pick up the whole call and the whole string rather
 * than stopping at the first space.
 *
 * @return the start offset, or null when the scan runs into unbalanced brackets.
 */
internal fun expressionStart(text: CharSequence, end: Int): Int? {
    var i = end
    while (i > 0) {
        when (val c = text[i - 1]) {
            ')', ']', '}' -> {
                i = matchingOpen(text, i - 1) ?: return null
            }
            '\'', '"' -> {
                i = stringStart(text, i - 1) ?: return null
            }
            '.' -> i--
            else -> if (c.isLetterOrDigit() || c == '_') i-- else return i
        }
    }
    return i
}

/**
 * Given [closeOffset] pointing at a closing bracket, returns the offset of its matching opener,
 * or null when the brackets do not balance.
 */
private fun matchingOpen(text: CharSequence, closeOffset: Int): Int? {
    var depth = 0
    var i = closeOffset
    while (i >= 0) {
        when (text[i]) {
            ')', ']', '}' -> depth++
            '(', '[', '{' -> {
                depth--
                if (depth == 0) return i
            }
            '\'', '"' -> {
                // A bracket inside a string is not a bracket.
                i = (stringStart(text, i) ?: return null) - 1
                continue
            }
        }
        i--
    }
    return null
}

/**
 * Given [quoteOffset] pointing at a closing quote, returns the offset of the opening quote, or null
 * when the literal is unterminated on this line.
 */
private fun stringStart(text: CharSequence, quoteOffset: Int): Int? {
    val quote = text[quoteOffset]
    var i = quoteOffset - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '\n') return null
        if (c == quote && !isEscaped(text, i)) return i
        i--
    }
    return null
}

/** True when the character at [offset] is preceded by an odd number of backslashes. */
private fun isEscaped(text: CharSequence, offset: Int): Boolean {
    var count = 0
    var i = offset - 1
    while (i >= 0 && text[i] == '\\') {
        count++
        i--
    }
    return count % 2 == 1
}
