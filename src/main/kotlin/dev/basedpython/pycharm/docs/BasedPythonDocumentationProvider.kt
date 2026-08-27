package dev.basedpython.pycharm.docs

import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.docs.render.ByDocstringComment
import dev.basedpython.pycharm.docs.render.ByDocstringSpans
import dev.basedpython.pycharm.docs.render.ByRenderedDocs
import dev.basedpython.pycharm.lang.BasedPythonFile
import java.util.function.Consumer

/**
 * Provides Quick Documentation (Ctrl+Q / F1) and external docs links for
 * basedpython-specific keywords, modifiers and operators in `.by` files.
 *
 * The PSI for `.by` files is flat (token-only), so this provider works off the
 * raw element text and the surrounding line in the document rather than a real
 * syntax tree. Anything it does not recognise yields `null`, allowing the LSP
 * hover to win where applicable.
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

    /** Resolve a [DocEntry] for the element by inspecting its text and the line around it. */
    private fun resolveEntry(element: PsiElement?): DocEntry? {
        if (element == null) return null
        if (element.containingFile !is BasedPythonFile) return null

        val token = element.text?.trim().orEmpty()

        // Direct operator / keyword hit on the element itself.
        directLookup(token)?.let { return it }

        // Fall back to inspecting the surrounding line text from the document.
        val file: PsiFile = element.containingFile ?: return null
        val doc: Document = file.viewProvider.document ?: return null
        val offset = element.textRange?.startOffset ?: return null
        if (offset < 0 || offset > doc.textLength) return null

        val lineNumber = doc.getLineNumber(offset.coerceAtMost(doc.textLength.coerceAtLeast(1) - 1).coerceAtLeast(0))
        val lineStart = doc.getLineStartOffset(lineNumber)
        val lineEnd = doc.getLineEndOffset(lineNumber)
        val line = doc.charsSequence.subSequence(lineStart, lineEnd).toString()

        return matchLine(line, token)
    }

    /** Exact keyword/operator match against the entry table. */
    private fun directLookup(token: String): DocEntry? {
        if (token.isEmpty()) return null
        BasedPythonDocEntries.ENTRIES[token]?.let { return it }
        // Operators may be glued to neighbouring tokens; check for embedded ops.
        if (token.contains("?.")) return BasedPythonDocEntries.ENTRIES["?."]
        if (token.contains("??")) return BasedPythonDocEntries.ENTRIES["??"]
        return null
    }

    /**
     * Inspect the whole line for multi-word constructs (e.g. `frozen data class`)
     * and operators, preferring the longest/most specific match that contains the
     * caret [token].
     */
    private fun matchLine(line: String, token: String): DocEntry? {
        val normalized = line.trim()

        // Operators anywhere on the line.
        if ("?." in normalized && (token == "?." || token == "?" || token == ".")) {
            BasedPythonDocEntries.ENTRIES["?."]?.let { return it }
        }
        if ("??" in normalized && (token == "??" || token == "?")) {
            BasedPythonDocEntries.ENTRIES["??"]?.let { return it }
        }

        // Multi-word class declarations, most specific first.
        when {
            Regex("""\bfrozen\s+data\s+class\b""").containsMatchIn(normalized) &&
                token in setOf("frozen", "data", "class") ->
                return BasedPythonDocEntries.ENTRIES["frozen data class"]

            Regex("""\bdata\s+class\b""").containsMatchIn(normalized) &&
                token in setOf("data", "class") ->
                return BasedPythonDocEntries.ENTRIES["data class"]

            Regex("""\benum\s+class\b""").containsMatchIn(normalized) &&
                token in setOf("enum", "class") ->
                return BasedPythonDocEntries.ENTRIES["enum class"]

            Regex("""\bclass\s+def\b""").containsMatchIn(normalized) &&
                token in setOf("class", "def") ->
                return BasedPythonDocEntries.ENTRIES["class def"]
        }

        // Single keyword fallback when the token itself is recognised.
        return BasedPythonDocEntries.ENTRIES[token]
    }
}
