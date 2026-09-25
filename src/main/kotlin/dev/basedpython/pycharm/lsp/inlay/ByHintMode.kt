package dev.basedpython.pycharm.lsp.inlay

/**
 * When a kind of hint is drawn: never, always, or only while the push key is held.
 *
 * The third one is what Rider calls *Push-to-Hint* and VS Code calls
 * `editor.inlayHints.enabled: offUnlessPressed` — hints that stay out of the way until you ask for
 * them, by holding a key rather than by opening settings. It is the mode that makes type hints
 * bearable on a file you already know: the code reads as written, and the inferred types are one
 * keypress away.
 *
 * Per kind rather than one switch for the lot, because the kinds are not read the same way. A
 * parameter name is worth having in front of you all the time; a `: T` on every binding is exactly
 * the thing most people want only when they are asking. So [ALWAYS] and [ON_PUSH] are separate
 * settings over the same three kinds, and holding the key shows the union of both.
 *
 * Persisted by [id]; the ids are part of the settings file format and must not change.
 */
enum class ByHintMode(val id: String, val display: String) {
    /** Not asked for, not drawn. */
    NEVER("never", "Never"),

    /** Drawn whenever the file is open. What every kind did before push-to-hint existed. */
    ALWAYS("always", "Always"),

    /** Drawn only while the push key is held down — see [ByPushKey]. */
    ON_PUSH("push", "While the push key is held"),
    ;

    /** Whether a hint in this mode is drawn right now, [pushed] being whether the key is down. */
    fun isShown(pushed: Boolean): Boolean = when (this) {
        NEVER -> false
        ALWAYS -> true
        ON_PUSH -> pushed
    }

    /**
     * Whether a hint in this mode is worth asking `by` for at all.
     *
     * An [ON_PUSH] hint counts: its inlay is built with the rest of them and merely draws nothing
     * until the key goes down, which is what makes the peek instant rather than a round trip to the
     * server (see [ByInlayHintPresentation]).
     */
    val isCollected: Boolean get() = this != NEVER

    companion object {
        /**
         * The mode a settings file means: the written one, or [fallback] where it says nothing.
         *
         * An unrecognised id falls back too, rather than throwing — a settings file written by a
         * newer plugin has to load and degrade, not fail.
         */
        fun resolve(id: String?, fallback: ByHintMode): ByHintMode =
            entries.firstOrNull { it.id == id } ?: fallback

        /**
         * The mode a boolean toggle meant.
         *
         * The three original settings were checkboxes, and are still written as booleans beside
         * their modes so a settings file reads in either version of the plugin. A project
         * configured before push-to-hint existed therefore falls back to this: on means [ALWAYS],
         * off means [NEVER], and nothing about that project's hints changes.
         */
        fun of(legacyEnabled: Boolean): ByHintMode = if (legacyEnabled) ALWAYS else NEVER
    }
}

/**
 * The mode every kind of hint is on — the settings as the rest of the plugin reads them.
 *
 * One value rather than a parameter per kind, because the kinds are `by`'s list and it grows: a
 * kind the server adds is one enum entry here, not another argument threaded through the provider,
 * the collector and every test that builds one.
 */
class ByHintModes(private val modes: Map<ByHintKind, ByHintMode>) {

    operator fun get(kind: ByHintKind): ByHintMode = modes[kind] ?: ByHintMode.ALWAYS

    /** Whether anything at all is worth asking `by` for. */
    val anyCollected: Boolean get() = ByHintKind.entries.any { this[it].isCollected }

    /**
     * What to send `by` as its `inlayHints` options: every kind it knows a name for, on unless the
     * setting says never.
     *
     * "Never" is the one mode the server can enforce itself, and enforcing it there means the hint
     * is not computed rather than computed and dropped. The other two both need the hint, since an
     * on-push hint is drawn from an inlay that was built before the key went down.
     */
    fun serverOptions(): Map<String, Boolean> = ByHintKind.entries
        .mapNotNull { kind -> kind.option?.let { it to (this[kind] != ByHintMode.NEVER) } }
        .toMap()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ByHintModes && ByHintKind.entries.all { this[it] == other[it] })

    override fun hashCode(): Int = ByHintKind.entries.map { this[it] }.hashCode()

    override fun toString(): String =
        ByHintKind.entries.joinToString(prefix = "ByHintModes(", postfix = ")") { "${it.name}=${this[it]}" }

    companion object {
        /** Every kind on the same mode — what a test usually wants. */
        fun all(mode: ByHintMode): ByHintModes =
            ByHintModes(ByHintKind.entries.associateWith { mode })
    }
}
