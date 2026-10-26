package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.template.postfix.settings.PostfixTemplatesSettings
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
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

    /**
     * `by` owns basedpython's postfix templates: `xs.for` completes to a `for` loop over `xs`, an item
     * labelled `for` whose detail is `postfix` and whose edit replaces all of `xs.for`. They are
     * offered when *Settings | Editor | General | Postfix Completion* would offer a language's own in
     * completion, as [ByPostfixCompletion.offered] reads it.
     */
    override fun createLookupElement(parameters: CompletionParameters, item: CompletionItem): LookupElement? {
        if (ByPostfixCompletion.isPostfix(item) && !ByPostfixCompletion.offered()) return null
        return super.createLookupElement(parameters, item)
    }
}

/** What makes one of `by`'s completions a postfix template, and when the platform's settings offer one. */
internal object ByPostfixCompletion {

    /** The detail `by` gives every postfix template's completion, and no other. */
    private const val DETAIL = "postfix"

    fun isPostfix(item: CompletionItem): Boolean = item.detail == DETAIL

    /**
     * Whether postfix templates are offered: *Enable postfix completion*. Measured in PyCharm 263.7528,
     * a `.py`'s completion lists the platform's `.for` and `.fore` on `xs.fo` exactly when it is on,
     * asked for or popped up alike, whatever *Show postfix templates in completion autopopup* says.
     */
    fun offered(settings: PostfixTemplatesSettings = PostfixTemplatesSettings.getInstance()): Boolean =
        settings.isPostfixTemplatesEnabled
}
