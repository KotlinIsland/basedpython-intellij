package dev.basedpython.pycharm.editor.templates

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

// ---------------------------------------------------------------------------
// Generic text-based surrounder
// ---------------------------------------------------------------------------

/**
 * Surrounds the selected text with [prefix] and [suffix], optionally appending
 * a colon and an indented body placeholder when [blockStyle] is true.
 */
class TextSurrounder(
    private val description: String,
    private val prefix: String,
    private val suffix: String,
    private val blockStyle: Boolean = false
) : Surrounder {

    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = true

    override fun surroundElements(
        project: Project,
        editor: Editor,
        elements: Array<out PsiElement>
    ): TextRange? {
        val selectionModel = editor.selectionModel
        val doc = editor.document

        val start = selectionModel.selectionStart
        val end = selectionModel.selectionEnd
        val selected = doc.charsSequence.subSequence(start, end).toString()

        val replacement = if (blockStyle) {
            // Determine existing indentation of the first selected line
            val lineNum = doc.getLineNumber(start)
            val lineStart = doc.getLineStartOffset(lineNum)
            val lineText = doc.charsSequence.subSequence(lineStart, start).toString()
            val indent = lineText.takeWhile { it == ' ' || it == '\t' }
            "$prefix$selected$suffix:\n$indent    "
        } else {
            "$prefix$selected$suffix"
        }

        doc.replaceString(start, end, replacement)

        val newEnd = start + replacement.length
        return if (blockStyle) {
            // Return range covering the body placeholder (empty — caret at end)
            TextRange(newEnd, newEnd)
        } else {
            TextRange(start + prefix.length, start + prefix.length + selected.length)
        }
    }
}

// ---------------------------------------------------------------------------
// Surrounder instances
// ---------------------------------------------------------------------------

val IF_SURROUNDER = TextSurrounder("if ...", "if ", "", blockStyle = true)
val WHILE_SURROUNDER = TextSurrounder("while ...", "while ", "", blockStyle = true)
val TRY_SURROUNDER = object : Surrounder {
    override fun getTemplateDescription() = "try / except"
    override fun isApplicable(elements: Array<out PsiElement>) = true
    override fun surroundElements(
        project: Project,
        editor: Editor,
        elements: Array<out PsiElement>
    ): TextRange? {
        val sel = editor.selectionModel
        val doc = editor.document
        val start = sel.selectionStart
        val end = sel.selectionEnd
        val selected = doc.charsSequence.subSequence(start, end).toString()

        val lineNum = doc.getLineNumber(start)
        val lineStart = doc.getLineStartOffset(lineNum)
        val lineText = doc.charsSequence.subSequence(lineStart, start).toString()
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }

        val body = selected.lines().joinToString("\n") { "$indent    $it" }
        val replacement = "try:\n$body\n${indent}except Exception as e:\n$indent    "
        doc.replaceString(start, end, replacement)
        val newEnd = start + replacement.length
        return TextRange(newEnd, newEnd)
    }
}
val PAREN_SURROUNDER = TextSurrounder("( ... )", "(", ")")
val BRACKET_SURROUNDER = TextSurrounder("[ ... ]", "[", "]")

// ---------------------------------------------------------------------------
// SurroundDescriptor
// ---------------------------------------------------------------------------

class BasedPythonSurroundDescriptor : SurroundDescriptor {

    private val surrounders = arrayOf(
        IF_SURROUNDER,
        WHILE_SURROUNDER,
        TRY_SURROUNDER,
        PAREN_SURROUNDER,
        BRACKET_SURROUNDER
    )

    override fun getElementsToSurround(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): Array<PsiElement> {
        // Return the element at startOffset — our surrounders work on raw text,
        // so any non-null element is sufficient.
        val elem = file.findElementAt(startOffset)
        return if (elem != null) arrayOf(elem) else PsiElement.EMPTY_ARRAY
    }

    override fun getSurrounders(): Array<Surrounder> = surrounders

    override fun isExclusive(): Boolean = false
}
