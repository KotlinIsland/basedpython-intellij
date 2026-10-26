package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import org.eclipse.lsp4j.CompletionItem

/**
 * `by`'s completion, its postfix templates offered where the platform's own would be.
 *
 * What an item is matched against is the platform's to work out from the item's `textEdit`, which `by`
 * gives every item: the range it replaces up to the caret is what has been typed of it, `car` in
 * `f"{car‸`, `xs.fo` for the postfix `for`. So inside an f-string's replacement field, where the
 * platform's own prefix for the one string token is `f"{car` and matched nothing, `by`'s items are
 * filtered by what was typed of the name.
 */
internal object ByCompletionSupport : LspCompletionSupport() {

    /** Not for smart completion, which is `by/smartCompletion`'s: [BySmartCompletionContributor]. */
    override fun shouldRunCodeCompletion(parameters: CompletionParameters): Boolean =
        BySmartCompletionContributor.platformRuns(parameters) && super.shouldRunCodeCompletion(parameters)

    /**
     * `by` owns basedpython's postfix templates: `xs.for` completes to a `for` loop over `xs`, an item
     * labelled `for` whose detail is `postfix` and whose edit replaces all of `xs.for`. They are listed
     * unless *Postfix templates* is switched off ([ByPostfixCompletion.offered]).
     */
    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        if (ByPostfixCompletion.isPostfix(item) && !ByPostfixCompletion.offered()) return null
        return super.createLookupElement(parameters, item)
    }
}

/** What makes one of `by`'s completions a postfix template, and whether they are listed. */
internal object ByPostfixCompletion {

    /** The detail `by` gives every postfix template's completion, and no other. */
    private const val DETAIL = "postfix"

    fun isPostfix(item: CompletionItem): Boolean = item.detail == DETAIL

    /**
     * Whether `by`'s postfix templates are listed: basedpython's own *Postfix templates* under *Settings
     * | Editor | General | Code Completion*. The platform's *Enable postfix completion*, which switches
     * PyCharm's own off, is not public API (`PostfixTemplatesSettings` is `@ApiStatus.Internal`), so a
     * `.by` file cannot follow it.
     */
    fun offered(settings: BasedPythonAppSettings = BasedPythonAppSettings.getInstance()): Boolean = settings.offerPostfixTemplates
}
