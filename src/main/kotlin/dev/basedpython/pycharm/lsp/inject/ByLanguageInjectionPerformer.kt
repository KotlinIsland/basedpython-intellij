package dev.basedpython.pycharm.lsp.inject

import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.lang.injection.general.Injection
import com.intellij.lang.injection.general.LanguageInjectionPerformer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import org.intellij.plugins.intelliLang.inject.TemporaryPlacesRegistry

/**
 * Puts a language the user chose into a `.by` string — Alt+Enter *Inject language or reference*,
 * or any other injection IntelliLang decides on — over the text the string holds, rather than over
 * everything between its quotes.
 *
 * The two differ for a triple-quoted string. basedpython strips the indentation its lines share,
 * so the html in
 *
 * ```
 * page = """
 *     <div>
 *       <p>hi</p>
 *     </div>
 *     """
 * ```
 *
 * is `<div>\n  <p>hi</p>\n</div>`, and the platform's own placement, one range between the quotes,
 * made the injected fragment the literal as written: an opening blank line, every line four spaces
 * in, and a last line of nothing but the closing quotes' indentation. Harmless to html, and wrong
 * for any language in which indentation means something.
 *
 * Which characters are the string's text is `by`'s answer, the one it gives for every string part
 * in `by/syntaxOutline` ([dev.basedpython.pycharm.lsp.outline.ByOutline.StringPart.text]): the runs
 * that survive the dedent, one per line, which is also how it reports a fragment it found a marker
 * for (see [BasedPythonLanguageInjector]). Each run is a place of the injection, so the fragment
 * reads as the program's string and every character of it still maps back to the file.
 *
 * Where there is no answer — no server, an answer for another revision, or a `by` that does not
 * report the runs — this declines, and the platform places the injection as it always has. Not
 * primary for the same reason: declining must leave the platform's placement in force.
 *
 * ## Only the user's own
 *
 * IntelliLang hands over more than what the user injected: its comment injector reads a
 * `# language=` comment off the text for any string no injector before it took, and reads it its
 * own way — a comment at the end of a line marks the statement on the *next* line. In a `.by` file
 * those comments are `by`'s to read (`by/injections`, through [BasedPythonLanguageInjector]), and
 * `by` reads a trailing one as marking the statement it ends and no other; with IntelliLang's
 * reading placed too, `loose = "x(y"  # language=RegExp` injected `loose`, from `by`, and the
 * unmarked statement under it, from IntelliLang (measured in PyCharm 263.7528). So an injection
 * that is not one the user placed ([TemporaryPlacesRegistry]) is taken here and not injected,
 * which keeps the platform's own placement from injecting it either.
 */
internal class ByLanguageInjectionPerformer : LanguageInjectionPerformer {

    override fun isPrimary(): Boolean = false

    override fun performInjection(registrar: MultiHostRegistrar, injection: Injection, context: PsiElement): Boolean {
        val host = context as? BasedPythonStringLiteral ?: return false
        // IntelliLang's reading of the text, which is `by`'s: taken, and nothing injected
        if (injection.supportId != TemporaryPlacesRegistry.SUPPORT_ID) return true
        if (!host.isValidHost) return false
        val language = injection.injectedLanguage ?: return false
        val places = placesIn(host) ?: return false

        registrar.startInjecting(language)
        places.forEachIndexed { index, range ->
            registrar.addPlace(
                injection.prefix.takeIf { index == 0 },
                injection.suffix.takeIf { index == places.lastIndex },
                host,
                range,
            )
        }
        registrar.doneInjecting()
        return true
    }

    /**
     * The runs of [host]'s text, relative to [host], or null when `by` has not said what they are
     * for the text [host] is in now.
     */
    private fun placesIn(host: BasedPythonStringLiteral): List<TextRange>? {
        val file = host.containingFile ?: return null
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return null
        // During completion this is a copy of the file with a dummy identifier typed in; the
        // outline is the original's.
        val anchor = CompletionUtil.getOriginalOrSelf(host)
        val part = outline.stringsIn(anchor.textRange).singleOrNull { it.range == anchor.textRange } ?: return null
        if (part.text.isEmpty()) return null
        val shift = CopyShift.from(anchor, host) ?: return null
        val start = host.textRange.startOffset
        val content = host.contentRange
        return part.text.map { run ->
            shift.map(run).shiftLeft(start).takeIf { content.contains(it) } ?: return null
        }
    }
}
