package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral

/**
 * An insertion made in a copy of a file, as a map from the original's offsets to the copy's.
 *
 * What `by` answers is about the file it was asked about, and the literal an injection is placed in
 * is usually in that same file. During completion it is not: the platform works on a copy with a
 * dummy identifier typed in at the caret — so a fragment reported at the end of the file is a dummy
 * identifier further along in the copy.
 */
internal class CopyShift private constructor(private val at: Int, private val inserted: Int) {

    fun map(range: TextRange): TextRange =
        TextRange(map(range.startOffset), map(range.endOffset))

    /**
     * An offset at the insertion point moves only if the text there moved, and the text *at* it
     * did not — which is what puts the caret's own dummy identifier inside the fragment being
     * completed in rather than after it.
     */
    private fun map(offset: Int): Int = if (offset >= at) offset + inserted else offset

    companion object {
        val NONE = CopyShift(Int.MAX_VALUE, 0)

        /**
         * How an offset in the file of [anchor], the literal `by` answered about, lands in the file
         * of [host], the literal being injected into. Null when the two differ by more than one
         * insertion, which is not a state this understands and so not one to guess at.
         */
        fun from(anchor: BasedPythonStringLiteral, host: BasedPythonStringLiteral): CopyShift? {
            if (anchor === host) return NONE
            val original = anchor.containingFile?.text ?: return null
            val copy = host.containingFile?.text ?: return null
            return between(original, copy)
        }

        /** The single insertion that turns [original] into [copy], or null when it is not one. */
        fun between(original: CharSequence, copy: CharSequence): CopyShift? {
            val inserted = copy.length - original.length
            if (inserted <= 0) return null
            var prefix = 0
            while (prefix < original.length && original[prefix] == copy[prefix]) prefix++
            var suffix = 0
            while (
                suffix < original.length - prefix &&
                original[original.length - 1 - suffix] == copy[copy.length - 1 - suffix]
            ) {
                suffix++
            }
            // Everything outside the insertion has to match, or this is not one insertion.
            if (prefix + suffix != original.length) return null
            return CopyShift(prefix, inserted)
        }
    }
}
