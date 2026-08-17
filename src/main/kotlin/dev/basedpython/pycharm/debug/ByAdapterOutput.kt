package dev.basedpython.pycharm.debug

/**
 * Which of the console's registers a DAP `output` event belongs in, or that it belongs in none.
 *
 * A console has three ways of saying something and they are not interchangeable — ordinary text
 * scrolls past, and something a person has to see must not scroll past with it.
 */
internal enum class ByOutputRegister {
    /** The program's own stdout. What the console is mostly for. */
    NORMAL,

    /** The debugger talking about itself: what it attached to, what it is about to do. */
    SYSTEM,

    /** Text a person has to see even with the console collapsed — stderr, and DAP's `important`. */
    PROMINENT,

    /** Not for a person at all. */
    HIDDEN,
}

/**
 * What to do with one `output` event from a debug adapter.
 *
 * Whether the events reach the console at all is [ByDebugBackend.ownsDebuggeeOutput] — under
 * debugpy they are a second copy of text the run console already has. This is the other half: given
 * that they are ours, where each one goes.
 *
 * The platform's `DapXDebugProcess` answers this in three lines — `console` is system output,
 * `stderr` is error output, everything else is stdout — and that is wrong in both directions for
 * the backends here. `telemetry` is defined by DAP as data for the client rather than text for a
 * person, and printing it puts adapter bookkeeping in front of the user. `important` is defined as
 * the thing a user should see *even if the console is collapsed*, and bpd uses it for exactly that:
 * a blind spot in subprocess tracking, a refused code replacement, a reminder that recording is on
 * and costing four times a bare run. Filed as ordinary stdout, each of those scrolls past under the
 * program's own output, which is the failure bpd's own comment on the category warns about.
 *
 * @see ByOutputRegister
 */
internal object ByAdapterOutput {

    /**
     * The register [category] belongs in, spelled as DAP spells the category.
     *
     * A `null` category is `console` rather than `stdout`: DAP says the field is optional and names
     * `console` as its default, and an adapter that omits it is talking rather than relaying.
     *
     * An unrecognised category is shown as ordinary output rather than dropped. DAP lets an adapter
     * invent categories, so meeting one means the vocabulary grew — and text of unknown importance
     * is still text somebody wrote to be read. Silence is the one answer that cannot be recovered
     * from.
     */
    fun registerFor(category: String?): ByOutputRegister = when (category) {
        null, CONSOLE -> ByOutputRegister.SYSTEM
        STDOUT -> ByOutputRegister.NORMAL
        STDERR, IMPORTANT -> ByOutputRegister.PROMINENT
        TELEMETRY -> ByOutputRegister.HIDDEN
        else -> ByOutputRegister.NORMAL
    }

    const val CONSOLE: String = "console"
    const val IMPORTANT: String = "important"
    const val STDOUT: String = "stdout"
    const val STDERR: String = "stderr"
    const val TELEMETRY: String = "telemetry"
}
