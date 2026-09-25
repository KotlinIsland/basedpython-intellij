package dev.basedpython.pycharm.editor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.StripTrailingSpacesFilter
import com.intellij.openapi.editor.StripTrailingSpacesFilterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import java.util.BitSet

/**
 * Keeps "strip trailing spaces on save" out of a `.by` file's string literals.
 *
 * Whitespace before a line break inside a triple-quoted string is part of the string: stripping it
 * changes what the program prints, and on the line that opens a string it changes whether
 * basedpython dedents the string at all. Java, Kotlin and Python each register a filter like this
 * one for the same reason.
 *
 * ## Where the strings come from
 *
 * `by`'s parser, through [ByOutlines]: the same string parts the margins and the string highlighting
 * are drawn from. Deciding here where a string starts and ends would be a second reading of the
 * source that could disagree with the compiler about a quote inside an f-string's interpolation, or
 * a prefix basedpython has and Python does not.
 *
 * ## When there is no answer for this text
 *
 * Saving runs on the EDT, where nothing waits for the server, so only an outline `by` has already
 * given for exactly this revision is used. Without one — the save came between an edit and its
 * answer, or no server is running — which lines are inside a string is not known, so no line is
 * stripped, and [ByOutlines.current] asks in the background for next time.
 *
 * The filter says so with [StripTrailingSpacesFilter.POSTPONED], whose contract is that the platform
 * tries the document again later. Measured on 263.5153, it does not: `StripTrailingSpacesUtil`
 * reports a postponed document as done and a `NOT_ALLOWED` one as the one to try again, the reverse
 * of the two constants' documentation. So in practice the lines of such a save keep their trailing
 * spaces until the file is next edited and saved. Returning `NOT_ALLOWED` to be retried would lean on
 * that inversion; `POSTPONED` is what is true here, and starts retrying once the platform does.
 *
 * An outline `by` declined to give (language services are off) is not an outline of a file with no
 * strings, so it strips nothing either: [StripTrailingSpacesFilter.NOT_ALLOWED].
 */
class ByStripTrailingSpacesFilterFactory : StripTrailingSpacesFilterFactory() {

    override fun createFilter(project: Project?, document: Document): StripTrailingSpacesFilter {
        val file = FileDocumentManager.getInstance().getFile(document)
            ?: return StripTrailingSpacesFilter.ALL_LINES
        if (!FileTypeRegistry.getInstance().isFileOfType(file, BasedPythonFileType.INSTANCE)) {
            return StripTrailingSpacesFilter.ALL_LINES
        }
        // No project, no server to ask, and nothing will ever say where the strings are.
        project ?: return StripTrailingSpacesFilter.NOT_ALLOWED
        val outline = ByOutlines.getInstance(project).current(document)
            ?: return StripTrailingSpacesFilter.POSTPONED
        if (outline.declined) return StripTrailingSpacesFilter.NOT_ALLOWED
        val kept = ByStringLines.of(document, outline)
        return StripTrailingSpacesFilter { line -> !kept.get(line) }
    }
}

/** Which lines of a document end inside a string literal. */
internal object ByStringLines {

    /**
     * The lines of [document] whose line break falls inside one of [outline]'s string parts.
     *
     * A line's trailing whitespace is the run just before its break, so it is inside a string
     * exactly when the break is: after the part's first character and before its last. The line a
     * string closes on is not included — whatever follows the closing quotes is code — and neither
     * is a string on one line. An f-string's interpolation is inside the part, so a line that ends
     * in one is kept as well: the expression's trailing spaces are code, but a format spec's are
     * the string's, and the outline does not say which the line ends in.
     *
     * [outline] must be of [document] as it is now, which [ByOutlines.current] guarantees.
     */
    fun of(document: Document, outline: ByOutline): BitSet {
        val kept = BitSet(document.lineCount)
        for (part in outline.strings) {
            val start = part.range.startOffset
            val end = part.range.endOffset
            for (line in document.getLineNumber(start)..document.getLineNumber(end)) {
                val lineEnd = document.getLineEndOffset(line)
                if (start < lineEnd && lineEnd < end) kept.set(line)
            }
        }
        return kept
    }
}
