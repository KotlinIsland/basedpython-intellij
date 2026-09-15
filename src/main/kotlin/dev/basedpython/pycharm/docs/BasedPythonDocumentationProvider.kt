package dev.basedpython.pycharm.docs

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.docs.render.ByDocstringComment
import dev.basedpython.pycharm.docs.render.ByDocstringSpans
import dev.basedpython.pycharm.docs.render.ByRenderedDocs
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import java.util.function.Consumer

/**
 * Provides Quick Documentation (Ctrl+Q / F1) and external docs links for
 * basedpython-specific keywords, modifiers and operators in `.by` files.
 *
 * The PSI for `.by` files is flat (token-only). A keyword whose meaning depends on the declaration
 * it is part of — `data`, `frozen`, `enum`, `class`, `def`, `protocol` — is looked up by what `by`
 * parsed that declaration as (see [ByOutlines]); anything else by its own text. Anything it does not
 * recognise yields `null`, allowing the LSP hover to win where applicable.
 *
 * It is also where rendered documentation is answered from — the docstrings the editor draws in
 * place of their source when "Render documentation comments" is on. That is a different axis on the
 * same extension point and shares nothing with the keyword table above: [collectDocComments] says
 * where the docstrings are, [generateRenderedDoc] says what each one looks like, and
 * [findDocComment] gets one back from a range for the gutter control. Both halves are the `by`
 * server's answers — see [ByDocstringSpans] and [ByRenderedDocs].
 */
class BasedPythonDocumentationProvider : DocumentationProvider {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val entry = resolveEntry(element ?: originalElement) ?: return null
        return buildString {
            append("<html><body style='font-family:sans-serif'>")
            append("<b>").append(entry.title).append("</b>")
            append("<br/>")
            append(entry.html)
            append("</body></html>")
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val entry = resolveEntry(element ?: originalElement) ?: return null
        return entry.title
    }

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): MutableList<String>? {
        val entry = resolveEntry(element ?: originalElement)
        val url = if (entry != null) {
            BasedPythonDocEntries.DOCS_BASE + entry.anchor
        } else {
            // Best-effort: only offer external docs for .by files.
            val target = element ?: originalElement
            if (target?.containingFile is BasedPythonFile) BasedPythonDocEntries.DOCS_BASE else return null
        }
        return mutableListOf(url)
    }

    // -------------------------------------------------------------------------
    // Rendered documentation
    // -------------------------------------------------------------------------

    /**
     * The docstrings the editor may render in place, as `by` reports them.
     *
     * `.by` has no doc-comment node to hand over — see [ByDocstringComment] — so these are fake
     * elements over the ranges the server pointed at, which is what the contract for this method
     * allows as long as [findDocComment] can produce an equal one for a range.
     */
    override fun collectDocComments(file: PsiFile, sink: Consumer<in PsiDocCommentBase>) {
        if (file !is BasedPythonFile) return
        val docstrings = ByDocstringSpans.of(file)
        // Ask the server here, where waiting is what a pass is for, so that pressing a gutter
        // control later is a lookup rather than a request. See [ByRenderedDocs.warm].
        ByRenderedDocs.warm(file, docstrings)
        docstrings.forEach { sink.accept(ByDocstringComment(file, it)) }
    }

    override fun generateRenderedDoc(comment: PsiDocCommentBase): String? {
        val docstring = comment as? ByDocstringComment ?: return null
        return ByRenderedDocs.html(docstring.containingFile, docstring.docstring)
    }

    /**
     * The doc comment at one range, which is what the gutter control asks for when it is clicked.
     *
     * Reads what a pass worked out, and asks the server for nothing — the platform answers a press
     * inside a `ReadAction.nonBlocking`, where a blocking request is cancelled by any write action
     * the IDE wants in the meantime. What it reads is kept against the file rather than against a
     * `PsiFile`, which the platform is free to drop and rebuild underneath an item that is still on
     * screen.
     */
    override fun findDocComment(file: PsiFile, range: TextRange): PsiDocCommentBase? {
        if (file !is BasedPythonFile) return null
        val docstring = ByDocstringSpans.cached(file).firstOrNull { it.range == range } ?: return null
        return ByDocstringComment(file, docstring)
    }

    /**
     * The [DocEntry] for [element]: a basedpython declaration keyword by what `by` parsed it as, and
     * anything else by its own text.
     */
    private fun resolveEntry(element: PsiElement?): DocEntry? {
        if (element == null) return null
        val file = element.containingFile as? BasedPythonFile ?: return null
        val token = element.text?.trim().orEmpty()
        if (token.isEmpty()) return null

        if (token in DECLARATION_WORDS) {
            // `data`, `enum` and `class` are ordinary names and keywords everywhere else, so what
            // they document is decided by the declaration the parser read them into — never by the
            // words around them on the line.
            val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return null
            return declarationEntry(outline, element.textRange.startOffset)
        }
        return BasedPythonDocEntries.ENTRIES[token]
    }

    /**
     * The entry for the declaration keyword at [offset]: the modifier it is part of (`frozen` in
     * `frozen data class`), or, on the `class` or `def` of a declaration, the modifier that makes it
     * the kind of declaration it is (the `class` of `data class P:`).
     */
    private fun declarationEntry(outline: ByOutline, offset: Int): DocEntry? {
        var entry: DocEntry? = null
        outline.walk { statement, _ ->
            val modifier = statement.modifiers.firstOrNull { it.range.containsOffset(offset) }
            if (modifier != null) {
                entry = MODIFIER_ENTRIES[modifier.name]?.let(BasedPythonDocEntries.ENTRIES::get)
                return@walk
            }
            val keyword = statement.clauses.firstOrNull()?.keyword ?: return@walk
            if (keyword.containsOffset(offset)) {
                entry = statement.modifiers
                    .firstNotNullOfOrNull { MODIFIER_ENTRIES[it.name] }
                    ?.let(BasedPythonDocEntries.ENTRIES::get)
            }
        }
        return entry
    }

    private companion object {
        /** The words whose entry depends on the declaration they are part of. */
        val DECLARATION_WORDS = setOf("data", "frozen", "enum", "class", "def", "protocol")

        /** A declaration modifier, as `by/syntaxOutline` names it, and the entry that explains it. */
        val MODIFIER_ENTRIES = mapOf(
            "data_class" to "data class",
            "frozen_data_class" to "frozen data class",
            "enum_def" to "enum class",
            "classmethod" to "class def",
            "protocol_class" to "protocol",
        )
    }
}
