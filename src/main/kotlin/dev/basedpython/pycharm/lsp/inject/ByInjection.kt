package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import dev.basedpython.pycharm.lsp.ext.ByInjectionFragment
import dev.basedpython.pycharm.lsp.ext.ByInjectionsResponse

/**
 * What decided that a fragment is in the language it is in.
 *
 * Carried through from the server because it is the only thing that can explain an injection whose
 * marker is nowhere near the string — a `propagated` fragment is in html because of an annotation
 * in another file, and a reader who cannot see why has no way to find out.
 */
enum class ByInjectionOrigin(val wire: String) {

    /** A `# language=` comment on the statement above. */
    COMMENT("comment"),

    /** The parameter the string is passed to declares the language. */
    DECLARED("declared"),

    /** That parameter hands it on to one that declares the language. */
    PROPAGATED("propagated"),
    ;

    companion object {
        /** The origin [wire] names, or null for one this plugin does not know. */
        fun of(wire: String?): ByInjectionOrigin? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One fragment of another language, in the offsets of the document it was asked about.
 *
 * [ranges] holds the runs whose text, joined in this order, is the fragment. More than one either
 * because it was written as adjacent literals — `"SELECT *" " FROM t"` is one query in two strings
 * — or because it is a triple-quoted string, whose indentation basedpython strips, leaving one run
 * per line.
 */
data class ByInjection(
    val language: String,
    val ranges: List<TextRange>,
    val origin: ByInjectionOrigin,
)

/**
 * Reading `by`'s `by/injections` replies, which is where the wire's line/character positions become
 * the offsets everything on this side works in.
 *
 * Pure apart from the document it measures against, and separate from the request for that reason:
 * an off-by-one here puts an injected fragment one character out, which is the kind of mistake that
 * is invisible until something is highlighted in the wrong place.
 */
object ByInjectionReplies {

    /**
     * The fragments in [response] that still fit [document].
     *
     * A fragment is dropped whole when any of its parts no longer maps — the document has moved on
     * since the server was asked, and a fragment whose second part is missing is not the same
     * fragment with a hole in it, it is a different string. Likewise one with no ranges at all, or
     * an unreadable origin: silently treating an answer this plugin does not understand as one it
     * does is how a wrong language ends up injected.
     */
    fun read(response: ByInjectionsResponse?, document: Document): List<ByInjection> =
        response?.injections.orEmpty().mapNotNull { fragment -> read(fragment, document) }

    private fun read(fragment: ByInjectionFragment, document: Document): ByInjection? {
        val language = fragment.language?.takeIf { it.isNotBlank() } ?: return null
        val origin = ByInjectionOrigin.of(fragment.origin) ?: return null
        if (fragment.ranges.isEmpty()) return null

        val ranges = fragment.ranges.map { range ->
            val start = getOffsetInDocument(document, range.start) ?: return null
            val end = getOffsetInDocument(document, range.end) ?: return null
            if (start > end) return null
            TextRange(start, end)
        }
        return ByInjection(language = language, ranges = ranges, origin = origin)
    }
}
