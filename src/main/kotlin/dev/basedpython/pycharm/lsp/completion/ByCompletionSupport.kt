package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.platform.lsp.api.customization.LspCompletionSupport

/**
 * `by`'s completion, its postfix templates among it where `completions.postfixTemplates` has them.
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
}
