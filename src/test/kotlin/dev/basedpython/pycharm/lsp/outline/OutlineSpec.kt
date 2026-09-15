package dev.basedpython.pycharm.lsp.outline

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange

/**
 * An outline written the way `by` would report it, for tests that exercise what is done with one.
 *
 * Every part is named by its text and found in the document in the order it is declared, so a spec
 * reads like the source it describes:
 *
 * ```
 * outline(document) {
 *     compound {
 *         clause("if x:") { simple("pass") }
 *         clause("else:") { call("print(y)") }
 *     }
 * }
 * ```
 *
 * Only the shapes the tests need, and only as `by` produces them — the golden reply in
 * `ByOutlineRepliesTest` is what pins this to the real server.
 */
class OutlineSpec private constructor(private val text: CharSequence) {

    private var cursor = 0
    private val strings = mutableListOf<ByOutline.StringPart>()

    private fun find(snippet: String): TextRange {
        val start = text.indexOf(snippet, cursor)
        require(start >= 0) { "`$snippet` is not in the text after offset $cursor" }
        cursor = start + snippet.length
        return TextRange(start, start + snippet.length)
    }

    inner class Suite {
        internal val statements = mutableListOf<ByOutline.Statement>()

        /** See [OutlineSpec.string]. */
        fun string(
            literal: String,
            strippedIndent: Int? = null,
            interpolations: List<String> = emptyList(),
            escapes: List<String> = emptyList(),
        ) = this@OutlineSpec.string(literal, strippedIndent, interpolations, escapes)

        /** A simple statement spelled [text]. */
        fun simple(text: String) {
            statements += ByOutline.Statement(find(text))
        }

        /** A call statement spelled [text], calling what comes before its first `(`. */
        fun call(text: String, positionalOnly: Boolean = true) {
            val range = find(text)
            val callee = text.substringBefore('(')
            val arguments = TextRange(range.startOffset + callee.length + 1, range.endOffset - 1)
            statements += ByOutline.Statement(
                range = range,
                call = ByOutline.Call(
                    callee = TextRange.from(range.startOffset, callee.length),
                    arguments = arguments.takeUnless { it.isEmpty },
                    positionalOnly = positionalOnly,
                ),
            )
        }

        /**
         * A compound statement, with [decorators] (the text of every line above its first clause)
         * and [modifiers] (each modifier's text to the name `by` gives it) before its clauses.
         */
        fun compound(
            decorators: String? = null,
            modifiers: List<Pair<String, String>> = emptyList(),
            clauses: Compound.() -> Unit,
        ) {
            val start = decorators?.let { find(it).startOffset }
            val modifierParts = modifiers.map { (spelled, name) -> ByOutline.Modifier(find(spelled), name) }
            val compound = Compound().apply(clauses)
            val first = compound.clauses.first().range
            statements += ByOutline.Statement(
                range = TextRange(
                    start ?: modifierParts.firstOrNull()?.range?.startOffset ?: first.startOffset,
                    compound.clauses.last().range.endOffset,
                ),
                clauses = compound.clauses,
                modifiers = modifierParts,
            )
        }
    }

    inner class Compound {
        internal val clauses = mutableListOf<ByOutline.Clause>()

        /**
         * A clause whose header is spelled [header] — from its keyword to its `:` — and whose
         * keyword is [keyword], by default the header's leading word.
         */
        fun clause(
            header: String,
            keyword: String? = header.takeWhile { it.isLetter() }.ifEmpty { null },
            body: Suite.() -> Unit = {},
        ) {
            val range = find(header)
            val suite = Suite().apply(body)
            clauses += ByOutline.Clause(
                keyword = keyword?.let { TextRange.from(range.startOffset + header.indexOf(it), it.length) },
                colon = if (header.endsWith(":")) TextRange(range.endOffset - 1, range.endOffset) else null,
                range = TextRange(range.startOffset, maxOf(range.endOffset, suite.statements.lastOrNull()?.range?.endOffset ?: 0)),
                body = suite.statements,
            )
        }
    }

    /**
     * A string literal part spelled [literal], stripped by [strippedIndent], with the given
     * [interpolations] and [escapes] spelled as they appear inside it, in order. Found from the start
     * of the text, independently of the statements.
     */
    private fun string(
        literal: String,
        strippedIndent: Int? = null,
        interpolations: List<String> = emptyList(),
        escapes: List<String> = emptyList(),
    ) {
        val range = run {
            val start = text.indexOf(literal, strings.lastOrNull()?.range?.endOffset ?: 0)
            require(start >= 0) { "`$literal` is not in the text" }
            TextRange.from(start, literal.length)
        }
        fun parts(spelled: List<String>): List<TextRange> {
            var from = range.startOffset
            return spelled.map { part ->
                val start = text.indexOf(part, from)
                require(start in range.startOffset until range.endOffset) { "`$part` is not in `$literal`" }
                from = start + part.length
                TextRange.from(start, part.length)
            }
        }
        strings += ByOutline.StringPart(range, strippedIndent, parts(interpolations), parts(escapes))
    }

    companion object {

        /** The outline of [document] as it is now, as [spec] describes it. */
        fun outline(document: Document, spec: Suite.() -> Unit): ByOutline {
            val outline = OutlineSpec(document.immutableCharSequence)
            val suite = outline.Suite().apply(spec)
            return ByOutline(document.modificationStamp, suite.statements, outline.strings)
        }

        /** [outline], put in as though `by` had just answered it for [document]. */
        fun remember(project: Project, document: Document, spec: Suite.() -> Unit): ByOutline =
            outline(document, spec).also { ByOutlines.getInstance(project).remember(document, it) }
    }
}
