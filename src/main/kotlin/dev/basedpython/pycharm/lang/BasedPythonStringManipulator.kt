package dev.basedpython.pycharm.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator

/**
 * How the platform rewrites the *content* of a `.by` string literal.
 *
 * Registered for [BasedPythonStringLiteral] as `lang.elementManipulator`, which is how anything
 * holding a host asks for its value or changes it without knowing what quotes it is written in:
 * `ElementManipulators.getValueText`, the reference machinery, and the *Inject language* intention
 * all go through here.
 *
 * The content handed in is *text*, not source — the same text
 * [dev.basedpython.pycharm.lang.BasedPythonStringEscaper] decoded — so writing it back means
 * spelling it as a literal again. That is the whole of [encode], and getting it wrong is how an
 * edit inside an injected fragment turns a working file into one that does not lex.
 */
class BasedPythonStringManipulator : AbstractElementManipulator<BasedPythonStringLiteral>() {

    override fun getRangeInElement(element: BasedPythonStringLiteral): TextRange = element.contentRange

    override fun handleContentChange(
        element: BasedPythonStringLiteral,
        range: TextRange,
        newContent: String,
    ): BasedPythonStringLiteral {
        val shape = element.shape ?: return element
        val text = element.text
        val written = encode(newContent, shape)
        val updated = buildString(text.length + written.text.length) {
            append(text, 0, range.startOffset)
            append(written.text)
            append(text, range.endOffset, text.length)
        }
        // A raw literal that has been given content no raw literal can spell loses its prefix. The
        // value is the same either way — `r"\d"` and `"\\d"` are the same string — and the
        // alternative is writing source that does not lex.
        val reprefixed = if (written.dropsRawPrefix) updated.removeRange(rawPrefixIn(updated)) else updated
        return element.updateText(reprefixed) as BasedPythonStringLiteral
    }

    /** Content spelled as literal source, and whether spelling it needed the `r` prefix gone. */
    private class Written(val text: String, val dropsRawPrefix: Boolean)

    private fun encode(content: String, shape: StringLiteralShape): Written = when {
        shape.isRaw && canBeRaw(content, shape) -> Written(content, dropsRawPrefix = false)
        shape.isRaw -> Written(escape(content, shape), dropsRawPrefix = true)
        else -> Written(escape(content, shape), dropsRawPrefix = false)
    }

    /**
     * Whether [content] can be written inside a raw literal at all.
     *
     * A raw literal has no escapes, so anything that would need one cannot go in it: the quote that
     * would close it, a line break in a literal that is not triple-quoted, and a trailing backslash
     * — which python refuses even in a raw string, because the backslash still escapes the quote on
     * the way through the tokenizer.
     */
    private fun canBeRaw(content: String, shape: StringLiteralShape): Boolean = when {
        content.endsWith('\\') -> false
        !shape.isTriple && (content.contains('\n') || content.contains('\r')) -> false
        !shape.isTriple && content.contains(shape.quote) -> false
        shape.isTriple && content.contains("${shape.quote}${shape.quote}${shape.quote}") -> false
        shape.isTriple && content.endsWith(shape.quote) -> false
        else -> true
    }

    /**
     * [content] spelled with the escapes a literal of this shape needs, and no others.
     *
     * A triple-quoted literal keeps its line breaks and all but the quotes that would close it
     * early: escaping every `"` in a block of html would make the result unreadable, which is the
     * opposite of what a multi-line literal is for.
     */
    private fun escape(content: String, shape: StringLiteralShape): String = buildString(content.length) {
        var index = 0
        while (index < content.length) {
            when (val character = content[index]) {
                '\\' -> append("\\\\")
                '\n' -> append(if (shape.isTriple) "\n" else "\\n")
                '\r' -> append(if (shape.isTriple) "\r" else "\\r")
                shape.quote -> if (needsEscaping(content, index, shape)) append('\\').append(character)
                else append(character)

                else -> append(character)
            }
            index++
        }
    }

    /**
     * Whether the quote at [index] would end the literal early.
     *
     * In a single-quoted literal, every one of them would. In a triple-quoted one, only a run of
     * three — or one at the very end, which would make a run of three with the closing quotes.
     */
    private fun needsEscaping(content: String, index: Int, shape: StringLiteralShape): Boolean {
        if (!shape.isTriple) return true
        if (index == content.length - 1) return true
        val run = "${shape.quote}${shape.quote}${shape.quote}"
        return (index - 2..index).any { start ->
            start >= 0 && start + 3 <= content.length && content.regionMatches(start, run, 0, 3)
        }
    }

    /**
     * The `r` in a literal's prefix, which is the only part of it [encode] ever removes.
     *
     * Searched inside the prefix rather than in the whole literal: `r"bar"` has two `r`s in it and
     * only the first one is the prefix.
     */
    private fun rawPrefixIn(text: String): IntRange {
        val prefixEnd = text.indexOfFirst { it == '"' || it == '\'' }.takeIf { it > 0 } ?: return IntRange.EMPTY
        val index = (0 until prefixEnd).firstOrNull { text[it].lowercaseChar() == 'r' } ?: return IntRange.EMPTY
        return index..index
    }
}
