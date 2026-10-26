package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * `{` typed inside an f-string or t-string in a `.by` file gets its `}`, and typing the `}` of an
 * interpolation over the one already there steps past it — what the platform does for a bracket in
 * code, done for a string's braces too.
 *
 * Where a string's text ends and an interpolation begins is `by`'s parse, which [ByOutlines] holds
 * for the text on screen. Without an answer for this text the key is the platform's, which pairs the
 * brace as well, the editor's lexer reading a field's braces
 * ([dev.basedpython.pycharm.lang.BasedPythonHighlightingLexer]).
 *
 * `{` typed between a pair it made — `f"{|}"` — makes `{{|}}`, the escaped brace, a pair again,
 * where the platform leaves `{{|}` (measured in PyCharm 263.7528, as it does for `.py`).
 */
class ByFStringBraceTypedHandler : TypedHandlerDelegate() {

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result {
        if (c != '{' && c != '}') return Result.CONTINUE
        if (file !is BasedPythonFile || editor.selectionModel.hasSelection() || editor.caretModel.caretCount > 1) {
            return Result.CONTINUE
        }
        if (!CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET) return Result.CONTINUE
        val document = editor.document
        val outline = ByOutlines.getInstance(project).current(document) ?: return Result.CONTINUE
        val offset = editor.caretModel.offset
        val part = interpolatedStringAt(outline, document.immutableCharSequence, offset) ?: return Result.CONTINUE

        if (c == '{') {
            document.insertString(offset, "{}")
            editor.caretModel.moveToOffset(offset + 1)
            return Result.STOP
        }
        // `}` over the `}` that closes the interpolation the caret is at the end of
        val closes = document.immutableCharSequence.getOrNull(offset) == '}' &&
            part.interpolations.any { it.endOffset == offset + 1 }
        if (!closes) return Result.CONTINUE
        editor.caretModel.moveToOffset(offset + 1)
        return Result.STOP
    }

    /**
     * The f-string or t-string part whose content [offset] is inside, between its quotes, if there
     * is one.
     */
    private fun interpolatedStringAt(outline: ByOutline, text: CharSequence, offset: Int): ByOutline.StringPart? {
        val part = outline.strings.firstOrNull { it.range.startOffset < offset && offset <= it.range.endOffset }
            ?: return null
        var index = part.range.startOffset
        var interpolated = false
        while (index < part.range.endOffset && text[index].isLetter()) {
            if (text[index].lowercaseChar() == 'f' || text[index].lowercaseChar() == 't') interpolated = true
            index++
        }
        if (!interpolated || index >= part.range.endOffset) return null
        val quote = text[index]
        val end = part.range.endOffset
        val quotes = if (index + 2 < end && text[index + 1] == quote && text[index + 2] == quote) 3 else 1
        val contentStart = index + quotes
        // a literal still being typed may not have its closing quotes yet
        val closed = end - quotes >= contentStart && (end - quotes until end).all { text[it] == quote }
        val contentEnd = if (closed) end - quotes else end
        return part.takeIf { offset in contentStart..contentEnd }
    }
}
