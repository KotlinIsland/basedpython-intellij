package dev.basedpython.pycharm.editor.templates

import com.intellij.lang.surroundWith.SurroundDescriptor
import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.typeinfo.ByTypeInfoProvider

/**
 * Surround With (Ctrl+Alt+T) in a `.by` file: statement surrounders for a selection of whole
 * statements, expression surrounders for a selection inside one, as Python's are split.
 *
 * Which a selection is comes from `by`'s outline of the text on screen ([ByOutlines]): a selection
 * is statements when, leading and trailing whitespace aside, it starts at a statement and ends at
 * the end of a later sibling — or of the same one. Anything shorter, inside a single simple
 * statement, is offered the expression surrounders. One descriptor used to offer every surrounder
 * for any selection, so `if`, `while` and `try` wrapped part of an expression in a statement, and
 * `if` and `while` took a run of statements for their condition.
 *
 * A selection inside a statement is an expression when `by/expressionTypes` says the smallest
 * expression holding it is exactly it — `float(size)` or `spec.partition`, and not `return Circle` or
 * `= spec`, which PyCharm offers no expression surrounder for either. That is asked as Type Info asks
 * it ([ByTypeInfoProvider.expressionsAround]): under a modal progress on the EDT, where the platform
 * computes the surrounders, and waited for off it. A `by` without the request, or one that does not
 * answer, offers the expression surrounders for any part of one simple statement, as before it.
 *
 * Before `by` has outlined the text on screen, or across statements without covering them, there is
 * nothing to surround here: the outline is read as it is on screen, and nothing guesses.
 */
internal object BySurroundSelections {

    /**
     * What a selection of [file] from [start] to [end] is, by the outline of its text and, inside a
     * statement, what [client] says the expressions there are.
     */
    fun of(
        file: PsiFile,
        start: Int,
        end: Int,
        client: LspClient? = file.originalFile.virtualFile?.let { byServerFor(file.project, it) },
    ): Selection? {
        val document = file.viewProvider.document ?: return null
        val outline = ByOutlines.getInstance(file.project).current(document) ?: return null
        val text = document.immutableCharSequence
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        if (from >= to) return null
        val range = TextRange(from, to)
        statements(outline.statements, from, to)?.let { return Selection.Statements(range) }
        val expressions = client?.let { ByTypeInfoProvider.expressionsAround(it, file, range) } ?: ByAnswer.Failed
        return when (expressions) {
            // `by` lists the smallest expression holding the selection first: it is one exactly when that is it
            is ByAnswer.Answer -> Selection.Expression(range).takeIf { expressions.value.first().textRange == range }
            ByAnswer.None -> null
            // a `by` without `by/expressionTypes`: any part of one simple statement, as before it
            ByAnswer.Failed -> inOneStatement(outline, from, to)?.let { Selection.Expression(range) }
        }
    }

    /** The run of sibling statements spanning exactly [from]..[to], searched at every depth. */
    private fun statements(siblings: List<ByOutline.Statement>, from: Int, to: Int): List<ByOutline.Statement>? {
        val first = siblings.indexOfFirst { it.range.startOffset == from }
        val last = siblings.indexOfLast { it.range.endOffset == to }
        if (first >= 0 && last >= first) return siblings.subList(first, last + 1)
        val enclosing = siblings.firstOrNull { it.range.startOffset <= from && to <= it.range.endOffset } ?: return null
        return enclosing.clauses.firstNotNullOfOrNull { statements(it.body, from, to) }
    }

    /** The simple statement holding all of [from]..[to], without being all of it. */
    private fun inOneStatement(outline: ByOutline, from: Int, to: Int): ByOutline.Statement? {
        var found: ByOutline.Statement? = null
        outline.walk { statement, _ ->
            if (statement.clauses.isEmpty() && statement.range.startOffset <= from && to <= statement.range.endOffset) {
                found = statement
            }
        }
        return found
    }

    sealed interface Selection {
        val range: TextRange

        /** One statement or several siblings, whole. */
        class Statements(override val range: TextRange) : Selection

        /** Part of one simple statement. */
        class Expression(override val range: TextRange) : Selection
    }
}

/** Offers [surrounders] for a selection of the kind [accepts] says, as one element over its range. */
internal abstract class BySurroundDescriptor(private val surrounders: Array<Surrounder>) : SurroundDescriptor {

    abstract fun accepts(selection: BySurroundSelections.Selection): Boolean

    override fun getElementsToSurround(file: PsiFile, startOffset: Int, endOffset: Int): Array<PsiElement> {
        val selection = BySurroundSelections.of(file, startOffset, endOffset) ?: return PsiElement.EMPTY_ARRAY
        if (!accepts(selection)) return PsiElement.EMPTY_ARRAY
        return arrayOf(ByRangeElement(file, selection.range))
    }

    override fun getSurrounders(): Array<Surrounder> = surrounders

    override fun isExclusive(): Boolean = false
}

/** `if`, `while`, `for`, `with`, `try`/`except` and `try`/`finally` around whole statements. */
internal class BasedPythonSurroundDescriptor : BySurroundDescriptor(
    arrayOf(
        StatementSurrounder("if", "if True:", select = "True"),
        StatementSurrounder("while", "while True:", select = "True"),
        StatementSurrounder("for", "for item in items:", select = "items"),
        StatementSurrounder("with", "with context:", select = "context"),
        StatementSurrounder("try / except", "try:", trailer = "except Exception:", trailerBody = "pass", select = "Exception"),
        StatementSurrounder("try / finally", "try:", trailer = "finally:", trailerBody = "pass", select = "pass"),
    ),
) {
    override fun accepts(selection: BySurroundSelections.Selection): Boolean =
        selection is BySurroundSelections.Selection.Statements
}

/** `( … )` and `[ … ]` around part of a statement. */
internal class BasedPythonExpressionSurroundDescriptor : BySurroundDescriptor(
    arrayOf(
        ExpressionSurrounder("( ... )", "(", ")"),
        ExpressionSurrounder("[ ... ]", "[", "]"),
    ),
) {
    override fun accepts(selection: BySurroundSelections.Selection): Boolean =
        selection is BySurroundSelections.Selection.Expression
}

/** Wraps the element's text in [prefix] and [suffix], and selects what it wrapped. */
internal class ExpressionSurrounder(
    private val description: String,
    private val prefix: String,
    private val suffix: String,
) : Surrounder {

    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.size == 1

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange {
        val range = elements.single().textRange
        val text = editor.document.charsSequence.subSequence(range.startOffset, range.endOffset)
        editor.document.replaceString(range.startOffset, range.endOffset, "$prefix$text$suffix")
        return TextRange.from(range.startOffset + prefix.length, text.length)
    }
}

/**
 * Puts the element's lines under [header], one level deeper, followed by [trailer] with
 * [trailerBody] under it when there is one, and selects [select] in what it wrote — the part a
 * person types next.
 */
internal class StatementSurrounder(
    private val description: String,
    private val header: String,
    private val trailer: String? = null,
    private val trailerBody: String? = null,
    private val select: String,
) : Surrounder {

    override fun getTemplateDescription(): String = description

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.size == 1

    override fun surroundElements(project: Project, editor: Editor, elements: Array<out PsiElement>): TextRange {
        val document = editor.document
        val range = elements.single().textRange
        val firstLine = document.getLineNumber(range.startOffset)
        val lastLine = document.getLineNumber(range.endOffset)
        val start = document.getLineStartOffset(firstLine)
        val end = document.getLineEndOffset(lastLine)
        val indent = indentOf(document, firstLine)
        val body = (firstLine..lastLine).joinToString("\n") { line ->
            val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
            if (text.isBlank()) "" else "$INDENT$text"
        }
        val written = buildString {
            append(indent).append(header).append('\n').append(body)
            if (trailer != null) {
                append('\n').append(indent).append(trailer)
                if (trailerBody != null) append('\n').append(indent).append(INDENT).append(trailerBody)
            }
        }
        document.replaceString(start, end, written)
        val at = written.lastIndexOf(select).takeIf { trailer != null } ?: written.indexOf(select)
        return TextRange.from(start + at, select.length)
    }

    private fun indentOf(document: Document, line: Int): String {
        val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
        return text.takeWhile { it == ' ' || it == '\t' }.toString()
    }

    private companion object {
        /** One level deeper; basedpython's indent is four spaces. */
        const val INDENT = "    "
    }
}
