package dev.basedpython.pycharm.lsp

import com.google.gson.JsonObject
import com.intellij.platform.lsp.api.LspClient

/**
 * The protocol extensions a running `by` says it answers, each a `true` under
 * `capabilities.experimental` in its `initialize` answer.
 *
 * Asked before a request that a `by` without it would answer `MethodNotFound`, so that the plugin
 * neither sends what cannot be answered nor reads that error as "nothing to say". A server that has
 * not answered `initialize` yet, or names no such capability, is one without it.
 */
internal object ByExperimental {

    /** `by/rename`: a rename's confirmable groups as change annotations. */
    const val RENAME_CHANGE_ANNOTATIONS: String = "renameChangeAnnotations"

    /** `by/checkName`: why a new name would be refused. */
    const val CHECK_NAME: String = "checkName"

    /** `by/nameSuggestions`: names for the symbol a rename is about. */
    const val NAME_SUGGESTIONS: String = "nameSuggestions"

    /** `by/prepareInvertBoolean` and `by/invertBoolean`. */
    const val INVERT_BOOLEAN: String = "invertBoolean"

    /** Whether [client] said at `initialize` that it answers the extension [name]. */
    fun advertises(client: LspClient, name: String): Boolean {
        val experimental = client.initializeResult?.capabilities?.experimental as? JsonObject ?: return false
        val value = experimental.get(name) ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }
}
