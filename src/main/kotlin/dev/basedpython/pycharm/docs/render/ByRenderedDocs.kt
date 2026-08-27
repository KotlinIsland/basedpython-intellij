package dev.basedpython.pycharm.docs.render

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByHover
import dev.basedpython.pycharm.lsp.ByHoverRequest
import dev.basedpython.pycharm.lsp.typeinfo.ByHoverMarkup
import java.util.Collections

/**
 * Turns a docstring into the HTML the editor renders in its place.
 *
 * ## Who renders what
 *
 * A docstring is not markdown. It is prose in one of several conventions — Google, NumPy,
 * reST/Sphinx, or none — and turning it into markdown is a real translation: PEP 257 indentation
 * trimming, `Args:` and `:param:` sections into headed lists, doctests into fences, bare
 * `__dunder__` escaped so it does not read as emphasis. `by` does all of it in `ty_ide`'s
 * `docstring.rs`, and its hover payloads carry the result. Doing it again here would be a second
 * implementation of a translation the server owns, always a version behind, and disagreeing with
 * the hover popup two keystrokes away.
 *
 * So the docstring's *meaning* comes from the server, and only the last step — markdown to the
 * IDE's documentation HTML — happens here, through the same [DocMarkdownToHtmlConverter] the
 * platform's own LSP hover uses. Rendered docs, Quick Documentation and the hover popup then agree,
 * because they are the same two pieces in the same order.
 *
 * The question is asked the only way the protocol allows: `textDocument/hover` at the name of the
 * symbol the docstring documents, which [ByDocstringSpans] worked out. The reply leads with the type
 * or signature and follows it with the rendered docstring, and [ByHoverMarkup.docstringMarkdown]
 * takes the second half. All of that happens in [warm], from the pass; [html] only reads.
 *
 * ## The one docstring the server cannot be asked about
 *
 * A module docstring documents the file, and a file has no name inside itself to hover — `by`
 * deliberately answers nothing for a literal expression, so hovering the docstring is not a way
 * round it. Those go through [ByDocstringText], which does not read the docstring as markdown at all.
 *
 * That distinction is the whole of it, and it matters more than it sounds. A raw docstring is not
 * markdown, and handing one to a markdown converter does not degrade gracefully — it invents
 * structure. A doctest is the plain case: `>>> int('0b100', base=0)` opens three levels of
 * blockquote in markdown and comes out as nested vertical rules with the `>>>` eaten, where `by`
 * would have fenced it. Rendering it as text keeps it dull and correct.
 *
 * The fix for the module case is a request in `by` that renders a document's docstrings outright,
 * which would also collapse every round trip below into one.
 *
 * ## Cost
 *
 * The rendering pass reruns whenever the PSI changes — every keystroke. What the server returns is
 * a function of the docstring text and nothing else, so [cache] keys on exactly that: typing
 * anywhere in a file costs no requests once its docstrings have been seen, and a docstring being
 * edited costs one request per distinct state it passes through.
 *
 * The cache is dropped only by its own size and by [clearCache], which the restart action calls: a
 * rebuilt `by` may translate a docstring differently, and nothing else would say so.
 */
internal object ByRenderedDocs {

    /**
     * Bounds the wait on a server that has stopped answering. Shorter than Type Info's: this runs
     * inside a highlighting pass, once per docstring the cache has not seen.
     */
    private const val HOVER_TIMEOUT_MS = 2_000

    private const val CACHE_SIZE = 512

    /** Docstring text to the markdown `by` made of it. Access is synchronized; passes run in parallel. */
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > CACHE_SIZE
        }
    )

    /**
     * The rendered block for a docstring already looked at, or `null` if there is none.
     *
     * Reads what [warm] left behind and asks the server for nothing. That matters more than it
     * looks: the platform computes the text for a gutter press inside a `ReadAction.nonBlocking`,
     * and a request blocking in there is cancelled by any write action the IDE happens to want —
     * which in a quiet project is never and in a working one is constantly. A press has to be an
     * answer, not an errand.
     */
    fun html(file: PsiFile, docstring: ByDocstring): String? {
        val literal = docstring.range.substring(file.text)
        cache[literal]?.let { return DocMarkdownToHtmlConverter.convert(file.project, it, BasedPythonLanguage) }
        return ByDocstringText.html(literal)
    }

    /**
     * Works out what each of [docstrings] says, for the pass to call while it is collecting them.
     *
     * This is where the server is asked and where the waiting belongs: a highlighting pass is
     * built to be cancelled and run again, and it is not a keystroke away from the user. By the
     * time anything presses a gutter control, [html] has an answer to hand.
     */
    fun warm(file: PsiFile, docstrings: List<ByDocstring>) {
        val text = file.text
        for (docstring in docstrings) {
            val literal = docstring.range.substring(text)
            if (literal in cache) continue
            serverMarkdown(file, docstring, literal)
        }
    }

    /** Drops what `by` has translated so far, for when the binary behind the server has changed. */
    fun clearCache() {
        cache.clear()
    }

    /** `by`'s rendering of this docstring, or `null` when it has none to give. */
    private fun serverMarkdown(file: PsiFile, docstring: ByDocstring, literal: String): String? {
        val offset = docstring.ownerNameOffset ?: return null
        cache[literal]?.let { return it }

        val markup = when (val hover = ByHoverRequest.at(file, offset, HOVER_TIMEOUT_MS)) {
            is ByHover.Markup -> hover.text
            ByHover.Nothing, ByHover.NoServer -> return null
        }
        return ByHoverMarkup.docstringMarkdown(markup)?.also { cache[literal] = it }
    }
}
