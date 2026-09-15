package dev.basedpython.pycharm.lsp.outline

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import dev.basedpython.pycharm.lsp.ext.ByOutlineClause
import dev.basedpython.pycharm.lsp.ext.ByOutlineStatement
import dev.basedpython.pycharm.lsp.ext.ByOutlineString
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineResponse
import org.eclipse.lsp4j.Range

/**
 * A document's block structure and string literals as `by`'s parser read them, in the offsets of
 * the document revision [stamp] names — see [ByOutlines] for where one comes from.
 *
 * Every offset here is only meaningful against that revision. A caller holding a document at any
 * other stamp has no business reading these, which is why [ByOutlines] hands one out only when the
 * stamps agree.
 */
class ByOutline(
    /** The `Document.modificationStamp` this outline describes. */
    val stamp: Long,
    /** The module's statements, in source order. */
    val statements: List<Statement>,
    /** Every string literal part, in source order. */
    val strings: List<StringPart>,
) {

    /** One statement: [range] runs from its first token, a decorator included, to its body's end. */
    data class Statement(
        val range: TextRange,
        /** A compound statement's clauses, in source order; empty for a simple statement. */
        val clauses: List<Clause> = emptyList(),
        val modifiers: List<Modifier> = emptyList(),
        val call: Call? = null,
    )

    /** One clause: `if`, `elif`, a `case`, a function's `def`. */
    data class Clause(
        /** Null on a clause with no keyword, which is a trailing lambda's. */
        val keyword: TextRange?,
        /** The `:` opening the suite; null when the header has none. */
        val colon: TextRange?,
        /** From the start of the header to the end of the body. */
        val range: TextRange,
        val body: List<Statement> = emptyList(),
    )

    /** A basedpython modifier keyword — `frozen data` — and what the parser read it as. */
    data class Modifier(val range: TextRange, val name: String)

    /** A call made as a statement of its own. */
    data class Call(
        val callee: TextRange,
        /** From the first argument to the last; null when there are none. */
        val arguments: TextRange?,
        /** Whether every argument is positional and none is unpacked. */
        val positionalOnly: Boolean,
    )

    /** One string literal part: one pair of quotes, the prefix, and what is between. */
    data class StringPart(
        val range: TextRange,
        /** How many leading characters basedpython strips from each line; null when none. */
        val strippedIndent: Int? = null,
        val interpolations: List<TextRange> = emptyList(),
        val escapes: List<TextRange> = emptyList(),
    )

    /** The string parts that overlap [range], in source order. */
    fun stringsIn(range: TextRange): List<StringPart> {
        // Parts are in source order and never overlap one another, so their ends are ordered too.
        var index = strings.binarySearchBy(range.startOffset) { it.range.endOffset }
            .let { if (it < 0) -it - 1 else it }
        val found = mutableListOf<StringPart>()
        while (index < strings.size && strings[index].range.startOffset < range.endOffset) {
            if (strings[index].range.intersectsStrict(range)) found += strings[index]
            index++
        }
        return found
    }

    /** Every statement, depth first in source order, with the clause it is a body statement of. */
    fun walk(visit: (statement: Statement, parent: Clause?) -> Unit) {
        fun walk(statements: List<Statement>, parent: Clause?) {
            for (statement in statements) {
                visit(statement, parent)
                for (clause in statement.clauses) walk(clause.body, clause)
            }
        }
        walk(statements, null)
    }
}

/**
 * Reading `by/syntaxOutline` replies: where the wire's line/character positions become offsets.
 *
 * Measured against the document as it is while being read, which must be the revision the server
 * was asked about — [ByOutlines] reads inside the same read action it asked in.
 */
object ByOutlineReplies {

    /**
     * The outline in [response], or null when it does not fit [document]: a position past the end
     * of a line, or a range running backwards, means the reply was about some other text, and an
     * outline with a statement missing is not the same outline with a hole in it.
     */
    fun read(response: BySyntaxOutlineResponse, document: Document): ByOutline? {
        val reader = Reader(document)
        return try {
            ByOutline(
                stamp = document.modificationStamp,
                statements = response.statements.map(reader::statement),
                strings = response.strings.map(reader::string),
            )
        } catch (_: Unfitting) {
            null
        }
    }

    /** Thrown by [Reader] at the first part that does not fit, and caught in [read]. */
    private object Unfitting : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader(private val document: Document) {

        fun range(range: Range?): TextRange {
            range ?: throw Unfitting
            val start = getOffsetInDocument(document, range.start) ?: throw Unfitting
            val end = getOffsetInDocument(document, range.end) ?: throw Unfitting
            if (start > end) throw Unfitting
            return TextRange(start, end)
        }

        fun statement(statement: ByOutlineStatement): ByOutline.Statement = ByOutline.Statement(
            range = range(statement.range),
            clauses = statement.clauses.map(::clause),
            modifiers = statement.modifiers.map {
                ByOutline.Modifier(range(it.range), it.name ?: throw Unfitting)
            },
            call = statement.call?.let {
                ByOutline.Call(range(it.callee), it.arguments?.let(::range), it.positionalOnly)
            },
        )

        fun clause(clause: ByOutlineClause): ByOutline.Clause = ByOutline.Clause(
            keyword = clause.keyword?.let(::range),
            colon = clause.colon?.let(::range),
            range = range(clause.range),
            body = clause.body.map(::statement),
        )

        fun string(string: ByOutlineString): ByOutline.StringPart = ByOutline.StringPart(
            range = range(string.range),
            strippedIndent = string.strippedIndent,
            interpolations = string.interpolations.map(::range),
            escapes = string.escapes.map(::range),
        )
    }
}
