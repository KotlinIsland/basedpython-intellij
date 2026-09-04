package dev.basedpython.pycharm.lang

/**
 * Turning the *source* of a string literal's content into the *text* it stands for, keeping track
 * of where each character came from.
 *
 * This is what makes an injected fragment the string the program will actually hold rather than the
 * characters typed to spell it: `"<a href=\"/\">"` is html reading `<a href="/">`, and an editor
 * shown the backslashes would report the escape as broken markup. The offset map is the other half
 * — an edit made inside the fragment has to land back on the right characters of the literal, and
 * a decoded character can stand for anything from one source character to ten (`\N{BULLET}`).
 *
 * Pure text, no PSI: [BasedPythonStringEscaper] is the platform-facing wrapper, and this is the
 * part worth testing on its own.
 */
object StringLiteralDecoding {

    /**
     * Appends the text [source] stands for to [out], recording for each appended character which
     * offset of [source] it came from.
     *
     * @param source the literal's content, quotes excluded
     * @param isRaw an `r`-prefixed literal, where a backslash is a backslash and nothing is decoded
     * @param out where the decoded text goes; appended to, so an existing prefix is kept
     * @param sourceOffsets filled from index 0 for each character appended, plus one past the last
     *   — so it must be at least `source.length + 1` long, which is enough because decoding never
     *   produces more characters than it consumes
     */
    fun decode(source: CharSequence, isRaw: Boolean, out: StringBuilder, sourceOffsets: IntArray) {
        val outStart = out.length

        fun emit(text: CharSequence, from: Int) {
            for (character in text) {
                sourceOffsets[out.length - outStart] = from
                out.append(character)
            }
        }

        var index = 0
        while (index < source.length) {
            val character = source[index]
            if (isRaw || character != '\\' || index + 1 >= source.length) {
                emit(character.toString(), index)
                index++
                continue
            }
            val escape = escapeAt(source, index)
            emit(escape.text, index)
            index += escape.length
        }
        // One past the end, so that an edit appended at the very end of the fragment maps to the
        // end of the content rather than to nothing.
        sourceOffsets[out.length - outStart] = source.length
    }

    /** What one escape sequence stands for, and how much of the source it takes up. */
    private class Escape(val text: CharSequence, val length: Int)

    /**
     * The escape starting at the backslash at [start].
     *
     * An escape python does not recognise is not an error: `"\q"` is a two-character string, a
     * backslash and a `q`, and that is what comes back here. The same goes for a malformed `\x`,
     * which python rejects at compile time but an editor sees constantly, half-typed — reporting
     * it as broken injected code would be reporting on the user's typing speed.
     */
    private fun escapeAt(source: CharSequence, start: Int): Escape {
        val verbatim = Escape(source.subSequence(start, start + 2), 2)
        return when (source[start + 1]) {
            '\n' -> Escape("", 2)
            // A line continuation written on windows, and a lone `\r` for a file with old mac line
            // endings that the editor has not normalised.
            '\r' -> if (start + 2 < source.length && source[start + 2] == '\n') {
                Escape("", 3)
            } else {
                Escape("", 2)
            }

            '\\' -> Escape("\\", 2)
            '\'' -> Escape("'", 2)
            '"' -> Escape("\"", 2)
            'n' -> Escape("\n", 2)
            't' -> Escape("\t", 2)
            'r' -> Escape("\r", 2)
            'b' -> Escape("\b", 2)
            'f' -> Escape("\u000C", 2)
            'v' -> Escape("\u000B", 2)
            'a' -> Escape("\u0007", 2)
            '0', '1', '2', '3', '4', '5', '6', '7' -> octal(source, start)
            'x' -> hex(source, start, digits = 2) ?: verbatim
            'u' -> hex(source, start, digits = 4) ?: verbatim
            'U' -> hex(source, start, digits = 8) ?: verbatim
            'N' -> named(source, start) ?: verbatim
            // `\q` keeps both characters, which is python's rule rather than a fallback.
            else -> verbatim
        }
    }

    /** `\ooo` — one to three octal digits, and never more however many follow. */
    private fun octal(source: CharSequence, start: Int): Escape {
        var end = start + 1
        while (end < source.length && end < start + 4 && source[end] in '0'..'7') end++
        val value = source.subSequence(start + 1, end).toString().toInt(8)
        return Escape(value.toChar().toString(), end - start)
    }

    /** `\xHH`, `\uHHHH`, `\UHHHHHHHH` — exactly [digits] hex digits, or not an escape at all. */
    private fun hex(source: CharSequence, start: Int, digits: Int): Escape? {
        val from = start + 2
        val to = from + digits
        if (to > source.length) return null
        if ((from until to).any { !isHexDigit(source[it]) }) return null
        val codePoint = source.subSequence(from, to).toString().toLong(16)
        if (codePoint > Character.MAX_CODE_POINT.toLong()) return null
        return Escape(String(Character.toChars(codePoint.toInt())), to - start)
    }

    /** `\N{LATIN SMALL LETTER A}` — a character named the way unicode names it. */
    private fun named(source: CharSequence, start: Int): Escape? {
        if (start + 2 >= source.length || source[start + 2] != '{') return null
        val close = source.indexOf('}', start + 3)
        if (close < 0) return null
        val name = source.subSequence(start + 3, close).toString()
        val codePoint = try {
            Character.codePointOf(name)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return Escape(String(Character.toChars(codePoint)), close - start + 1)
    }

    private fun isHexDigit(character: Char): Boolean =
        character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'

    private fun CharSequence.indexOf(character: Char, from: Int): Int {
        for (index in from until length) if (this[index] == character) return index
        return -1
    }
}
