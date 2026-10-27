package dev.basedpython.pycharm.console

/**
 * What each line typed into a console becomes on `by repl`'s standard input, decided when something
 * reads it: framed as an input when the session waits for its next one, and as it is when the
 * program reads a line — `input()` — or an input sent unframed goes on.
 *
 * Decided then rather than when it is typed, because a line typed while an input runs belongs to
 * whatever reads next, as typed-ahead text does in a terminal: an `input()` the running code is
 * about to call, or the session's next input. Written in the order typed, one for each read.
 *
 * [alwaysInput] is a `by` that cannot say what it reads (one from before `--events`): every line is
 * an input, framed and written at once, as the console did before there was a way to know — and a
 * program's `input()` then reads the frame's markers with the line.
 */
internal class ByReplInput(
    private val write: (String) -> Unit,
    /** Told of each submission sent as an input, in the order `by repl` numbers them. */
    private val sentAsInput: (Submission) -> Unit,
    private val alwaysInput: Boolean,
) {

    /** A line or lines typed and run, and where the console printed it, for an input to be found by its number. */
    class Submission(val text: String, val place: Any?)

    private val pending = ArrayDeque<Submission>()

    /** What reads `by repl`'s standard input now, or null while nothing has said it reads. */
    private var reader: ByReplEvents.Reading? = if (alwaysInput) ByReplEvents.Reading.INPUT else null

    val readsLine: Boolean
        @Synchronized get() = reader == ByReplEvents.Reading.PROGRAM || reader == ByReplEvents.Reading.CONTINUATION

    @Synchronized
    fun submit(submission: Submission) {
        pending.addLast(submission)
        drain()
    }

    /** `by repl` reads its standard input for [reading]. */
    @Synchronized
    fun reading(reading: ByReplEvents.Reading) {
        if (alwaysInput) return
        reader = reading
        drain()
    }

    private fun drain() {
        while (true) {
            val reading = reader ?: return
            val next = pending.removeFirstOrNull() ?: return
            when (reading) {
                ByReplEvents.Reading.INPUT -> {
                    // a blank input is skipped without being numbered, as python's own repl skips it,
                    // so nothing is sent for it and the session still waits for one
                    if (!ByConsoleText.isInput(next.text)) continue
                    sentAsInput(next)
                    write(ByConsoleText.framed(next.text))
                }
                ByReplEvents.Reading.CONTINUATION, ByReplEvents.Reading.PROGRAM -> write(ByConsoleText.line(next.text))
            }
            // one read, one line: what reads next says so
            if (!alwaysInput) reader = null
        }
    }
}
