package dev.basedpython.pycharm.editor.djangotemplate

import dev.basedpython.pycharm.editor.djangotemplate.ByDjangoTemplateTypedHandler.Companion.closerFor
import dev.basedpython.pycharm.editor.djangotemplate.ByDjangoTemplateTypedHandler.Companion.insertionFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** When typing the second character of a django delimiter closes it, and with what. */
class ByDjangoTemplateTypedHandlerTest {

    /** [text] as it stands just after its last character was typed, the caret at its end unless `‸` says otherwise. */
    private fun after(text: String): String? {
        val caret = text.indexOf('‸').takeIf { it >= 0 } ?: text.length
        val plain = text.replace("‸", "")
        val typed = plain[caret - 1]
        val closer = closerFor(typed) ?: return null
        return insertionFor(typed, closer, plain, caret)
    }

    @Test
    fun `each opening delimiter gets its closer`() {
        assertEquals("  %}", after("<p>{%"))
        assertEquals("  }}", after("<p>{{"))
        assertEquals("  #}", after("<p>{#"))
    }

    @Test
    fun `a lone brace or percent is left as typed`() {
        assertNull(after("width: 100%"))
        assertNull(after("<p>{"))
        assertNull(after("# heading"))
    }

    /** `{{{` is three braces someone meant, not a variable tag opened twice. */
    @Test
    fun `a third brace is left as typed`() {
        assertNull(after("{{{"))
    }

    /** Retyping the `%` of an existing tag must not close it a second time. */
    @Test
    fun `an already closed delimiter is left alone`() {
        assertNull(after("{%‸%}"))
        assertNull(after("{%‸ %}"))
        assertNull(after("{{‸}}"))
        assertNull(after("{#‸  #}"))
    }

    @Test
    fun `a closer further along the line does not count`() {
        assertEquals("  %}", after("{%‸ <b>text</b> %}"))
    }

    @Test
    fun `other characters are not delimiters`() {
        assertNull(closerFor('}'))
        assertNull(closerFor('a'))
    }
}
