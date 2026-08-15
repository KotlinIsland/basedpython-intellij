package dev.basedpython.pycharm.debug.bpd

/**
 * Which debugger drives a `.by` session.
 *
 * Two backends, and they are not the same shape underneath — one attaches to a port the debuggee
 * opens, the other *is* the interpreter `by run` starts. What they share is DAP, which is why the
 * choice can be a setting at all rather than two plugins.
 *
 * Stored as a `String` rather than as this enum, for the reason `ByCommonOptions.environment` is:
 * the settings serializer persists a constant's name and throws on one it cannot match, so a
 * settings file written by a newer plugin would fail to load instead of falling back.
 */
enum class ByDebugBackend {
    /**
     * `bpd`, the basedpython debugger.
     *
     * The default. It is PEP 669 native — a line with no breakpoint on it is `DISABLE`d the first
     * time it is seen — and it reports `.by` locations through the source map itself rather than
     * through pydevd's generated-code support. It is also the only backend that answers
     * `bpd/facts`, which is what the data-flow analysis is seeded from.
     */
    BPD,

    /**
     * debugpy, through pydevd's `setPydevdSourceMap`.
     *
     * Kept because it is what shipped, because it needs no extra binary — `pip install debugpy`
     * and nothing else — and because a bug in one backend should not leave `.by` undebuggable.
     */
    DEBUGPY,
    ;

    companion object {
        /**
         * The backend a setting names, or [BPD] when it names nothing recognisable.
         *
         * Falling back rather than failing: an unreadable backend name is a settings file from
         * another version, and refusing to debug at all over it would be a worse answer than
         * using the default one.
         */
        fun of(setting: String?): ByDebugBackend = when (setting?.trim()?.lowercase()) {
            "debugpy" -> DEBUGPY
            else -> BPD
        }

        /** What [of] reads, so a settings UI and a test spell it the same way. */
        fun settingFor(backend: ByDebugBackend): String = when (backend) {
            BPD -> "bpd"
            DEBUGPY -> "debugpy"
        }
    }
}
