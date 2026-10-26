package dev.basedpython.pycharm.lsp.inject

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiErrorElement
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral

/**
 * Leaves a fragment's syntax errors to `by` where `by` reports them: in a regular expression `re.compile`
 * is given, `by`'s `invalid-regex` says `unterminated character set at position 3` at the `[`, and the
 * RegExp plugin's parser said `Unclosed character class` at the closing quote as well, the one mistake
 * reported twice. A fragment `by` marks `checked` ([ByInjection.checked]) has its parser's error
 * elements left unhighlighted; its colours, and every other fragment's errors, are untouched. Which
 * fragments those are is `by`'s to say: it checks a pattern passed to `re` but not one marked with a
 * `# language=` comment, which only the parser checks.
 */
internal class ByInjectedErrorFilter : HighlightErrorFilter() {

    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        val manager = InjectedLanguageManager.getInstance(element.project)
        val host = manager.getInjectionHost(element) as? BasedPythonStringLiteral ?: return true
        val offset = manager.injectedToHost(element, element.textRange.startOffset)
        val injection = ByInjections.getInstance(element.project).forFile(host.containingFile)
            .firstOrNull { fragment -> fragment.ranges.any { offset in it.startOffset..it.endOffset } }
            ?: return true
        return !injection.checked
    }
}
