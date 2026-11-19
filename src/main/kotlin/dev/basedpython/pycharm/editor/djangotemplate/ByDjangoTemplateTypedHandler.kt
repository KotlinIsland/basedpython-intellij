package dev.basedpython.pycharm.editor.djangotemplate

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Typing the second character of a django delimiter closes it, with the caret between the two
 * halves: `{%` becomes `{% ‸ %}`, `{{` becomes `{{ ‸ }}` and `{#` becomes `{# ‸ #}` — what PyCharm
 * Pro's Django templates do, and what makes tag completion land somewhere it can be finished.
 *
 * Only in a template [ByDjangoTemplateEditing] handles, and only when the delimiter is not already
 * closed: retyping the `%` of an existing `{% if %}` leaves it alone.
 */
class ByDjangoTemplateTypedHandler : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        val closer = closerFor(c) ?: return Result.CONTINUE
        if (!ByDjangoTemplateEditing.handles(file)) return Result.CONTINUE
        val document = editor.document
        val caret = editor.caretModel.offset
        val insertion = insertionFor(c, closer, document.immutableCharSequence, caret) ?: return Result.CONTINUE
        document.insertString(caret, insertion)
        editor.caretModel.moveToOffset(caret + 1)
        return Result.STOP
    }

    internal companion object {
        /** The closing half for the second character of an opening delimiter, or `null` for any other character. */
        fun closerFor(c: Char): String? = when (c) {
            '%' -> "%}"
            '{' -> "}}"
            '#' -> "#}"
            else -> null
        }

        /**
         * What to insert at [caret], just after [c] was typed into [text], or `null` to insert
         * nothing: `" ‸ %}"` without the caret, which is placed after the first space.
         *
         * The character before the one typed has to be a single `{` — so a third `{` in `{{{` is
         * left as typed — and what follows the caret must not already close the delimiter.
         */
        fun insertionFor(c: Char, closer: String, text: CharSequence, caret: Int): String? {
            val typed = caret - 1
            if (typed < 1 || text.getOrNull(typed) != c) return null
            if (text[typed - 1] != '{') return null
            if (typed >= 2 && text[typed - 2] == '{') return null
            val rest = text.subSequence(caret, minOf(text.length, caret + LOOKAHEAD)).toString()
            if (rest.trimStart(' ').startsWith(closer)) return null
            return "  $closer"
        }

        /** How far past the caret an existing closer is looked for: a space or two and the closer. */
        private const val LOOKAHEAD = 8
    }
}
