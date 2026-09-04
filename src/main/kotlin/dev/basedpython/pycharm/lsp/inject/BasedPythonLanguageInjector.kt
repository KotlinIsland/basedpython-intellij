package dev.basedpython.pycharm.lsp.inject

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral

/**
 * Puts another language inside a `.by` string, where `by` says there is one.
 *
 * Nothing about *which* language is decided here, and that is the point: a marker can be a comment
 * above the statement, an `Annotated[str, "language=…"]` on a parameter in another module, or that
 * same annotation reached through a function that only hands the value on. All three are questions
 * about the program, `by` answers them from the project it has already resolved, and this turns the
 * answer into the platform's shape for it. See [dev.basedpython.pycharm.lsp.inject.ByInjections].
 *
 * ## What the ranges are
 *
 * A fragment is one or more runs of the file's text which, joined, are what the string stands for.
 * One run for an ordinary literal; one per literal for a fragment written as adjacent strings; and
 * one per *line* for a triple-quoted string, whose indentation basedpython strips, so that the
 * fragment is the text without it. Every run is checked against the live PSI before it is used — it
 * has to be content of a literal that is still a host — so an answer a revision out of date fails
 * to match and the fragment goes uninjected for one pass, rather than being injected over
 * characters that are no longer there.
 *
 * ## Order against IntelliLang
 *
 * The platform hands an element to each registered injector in turn and stops at the first that
 * injects something. IntelliLang's own adapter — the one behind `# language=` comments in every
 * other language — is registered `order="last"`, so this one is asked first and wins wherever `by`
 * has an answer. Where it has none, because the server is not running, IntelliLang's comment
 * injector still reads a `# language=` comment off the text, which is a better fallback than
 * nothing.
 */
class BasedPythonLanguageInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(BasedPythonStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? BasedPythonStringLiteral ?: return
        if (!host.isValidHost) return
        val file = host.containingFile ?: return

        val injections = ByInjections.getInstance(file.project).forFile(file)
        if (injections.isEmpty()) return

        // During completion the platform works on a copy of the file with a dummy identifier typed
        // into it, so this host's offsets are not the ones the server answered about. Its original
        // is, and that is what the fragments are matched against.
        val anchor = CompletionUtil.getOriginalOrSelf(host) as? BasedPythonStringLiteral ?: return
        val anchorContent = absoluteContentRange(anchor) ?: return

        for (injection in injections) {
            val first = injection.ranges.firstOrNull() ?: continue
            // Only the literal the fragment *starts* in registers it. A fragment written as several
            // adjacent literals is one injection covering all of them, not one per literal.
            if (!anchorContent.contains(first)) continue
            val language = ByInjectedLanguages.find(injection.language) ?: continue
            val places = placesFor(injection, host, anchor, file) ?: continue

            registrar.startInjecting(language)
            for (place in places) registrar.addPlace(null, null, place.host, place.range)
            registrar.doneInjecting()
            return
        }
    }

    /** One literal's contribution to a fragment: the host, and the range inside it to inject. */
    private class Place(val host: BasedPythonStringLiteral, val range: TextRange)

    /**
     * Every part of [injection] as a place, or null when any of them cannot be placed.
     *
     * All or nothing, because a client joins the parts to get the fragment's text: a part left out
     * does not shorten the fragment, it shifts everything after it onto the wrong characters.
     *
     * A fragment has more than one part in two shapes, and both end up here. It may be written as
     * adjacent literals, in which case each part is in a different one; or it may be a dedented
     * triple-quoted string, whose text is what survives the indentation on each line and so is
     * several runs of the *same* literal.
     */
    private fun placesFor(
        injection: ByInjection,
        host: BasedPythonStringLiteral,
        anchor: BasedPythonStringLiteral,
        file: PsiFile,
    ): List<Place>? {
        val shift = shiftFrom(anchor, host) ?: return null
        val places = ArrayList<Place>(injection.ranges.size)
        // Nearly always the same literal as the part before, so it is worth trying before walking
        // the file again — a fragment in a fifty-line string is fifty parts of one literal.
        var last: BasedPythonStringLiteral = host

        for (range in injection.ranges) {
            val shifted = shift.map(range)
            val part = last.takeIf { holdsContent(it, shifted) } ?: literalAt(file, shifted) ?: return null
            val partRange = shifted.shiftLeft(part.textRange.startOffset)
            if (partRange.isEmpty) return null
            places.add(Place(part, partRange))
            last = part
        }
        return places.takeIf { it.isNotEmpty() }
    }

    /**
     * How an offset in the file `by` answered about lands in the file being injected into.
     *
     * The two are the same file except during completion, where the platform works on a copy with a
     * dummy identifier typed in at the caret — so a fragment reported at the end of the file is a
     * dummy identifier further along in the copy. Null when the two differ by more than that one
     * insertion, which is not a state this understands and so not one to guess at.
     */
    private fun shiftFrom(anchor: BasedPythonStringLiteral, host: BasedPythonStringLiteral): Shift? {
        if (anchor === host) return Shift.NONE
        val original = anchor.containingFile?.text ?: return null
        val copy = host.containingFile?.text ?: return null
        return Shift.between(original, copy)
    }

    /** An insertion made in a copy of a file, as a map from the original's offsets to the copy's. */
    private class Shift(private val at: Int, private val inserted: Int) {

        fun map(range: TextRange): TextRange =
            TextRange(map(range.startOffset), map(range.endOffset))

        /**
         * An offset at the insertion point moves only if the text there moved, and the text *at* it
         * did not — which is what puts the caret's own dummy identifier inside the fragment being
         * completed in rather than after it.
         */
        private fun map(offset: Int): Int = if (offset >= at) offset + inserted else offset

        companion object {
            val NONE = Shift(Int.MAX_VALUE, 0)

            /** The single insertion that turns [original] into [copy], or null when it is not one. */
            fun between(original: CharSequence, copy: CharSequence): Shift? {
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
                return Shift(prefix, inserted)
            }
        }
    }

    /** The literal whose content holds [range], or null when nothing there is one. */
    private fun literalAt(file: PsiFile, range: TextRange): BasedPythonStringLiteral? {
        val literal = PsiTreeUtil.findElementOfClassAtOffset(
            file,
            range.startOffset,
            BasedPythonStringLiteral::class.java,
            false,
        ) ?: return null
        return literal.takeIf { holdsContent(it, range) }
    }

    /** Whether [range] is content of [literal] — inside its quotes, and a host at all. */
    private fun holdsContent(literal: BasedPythonStringLiteral, range: TextRange): Boolean =
        literal.isValidHost && absoluteContentRange(literal)?.contains(range) == true

    /** A literal's content range in the file's own offsets, or null when it does not parse. */
    private fun absoluteContentRange(literal: BasedPythonStringLiteral): TextRange? {
        val shape = literal.shape ?: return null
        return TextRange(shape.contentStart, shape.contentEnd).shiftRight(literal.textRange.startOffset)
    }
}
