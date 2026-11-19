package dev.basedpython.pycharm.lsp.inlay

/**
 * A kind of inlay hint, by `by`'s own name for it: the `inlayHints` option that switches it, which is
 * also what `by` tags each hint with (`data.kind`, see [ByInlayHints.kindOf]).
 *
 * Only the name. What the kinds are — their titles, groups, descriptions and examples — is `by`'s to
 * say, in `by/inlayHintCatalog` ([ByInlayCatalog]). The plugin used to keep its own list of them, and
 * it drifted: it called return-type hints `inferredReturnTypes` where `by` says `returnTypes`, so
 * that setting switched nothing and those hints were filed as unknown, and it had no entry for two
 * kinds `by` had added since. A kind is now whatever `by` names, and a new one is configurable the
 * day the server has it.
 *
 * Settings are keyed by [setting] too ([dev.basedpython.pycharm.settings.BasedPythonSettings]), so
 * the plugin's settings file and the server's options read alike.
 */
@JvmInline
value class ByHintKind(val setting: String) {

    /** A hint with no tag at all, from a `by` older than the tags: it can only follow the default. */
    val isUntagged: Boolean get() = setting.isEmpty()

    override fun toString(): String = setting.ifEmpty { "<untagged>" }

    companion object {
        val UNTAGGED: ByHintKind = ByHintKind("")
    }
}
