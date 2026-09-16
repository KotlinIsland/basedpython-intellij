package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.process.BaseOSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key

/**
 * The last of what a process wrote to its own stdout and stderr, and how it ended.
 *
 * What `by run` said is the only account of why it ended before a debug session could start — a
 * refused interpreter, a transpile error, a launcher it could not run — and the platform shows none
 * of it: a debug start that fails before the adapter connects never opens the run console the text
 * went to. So a session keeps the end of it, to put in front of the user when that happens.
 *
 * Attached before the process is started ([dev.basedpython.pycharm.run.ByCommandLineState.infrastructureListeners]),
 * so nothing it wrote is missed. Bounded, because a program that runs for hours before it fails
 * writes far more than a sentence can carry. The IDE's own lines — the command line, "Process
 * finished" — are left out: they are not what the process said.
 */
class ByProcessTail(private val limit: Int = LIMIT) : ProcessListener {

    private val lock = Any()
    private val kept = StringBuilder()

    /**
     * The exit code of a process that ended when nobody asked it to, or null — while it runs, and
     * for good once Stop (or anything else) destroyed it. A process that was stopped did not refuse
     * anything, and what it had printed by then is not a reason.
     */
    @Volatile
    var endedOnItsOwnWith: Int? = null
        private set

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (!ProcessOutputType.isStdout(outputType) && !ProcessOutputType.isStderr(outputType)) return
        synchronized(lock) {
            kept.append(event.text)
            if (kept.length > limit) kept.delete(0, kept.length - limit)
        }
    }

    /**
     * Told before the platform reacts to the end — it stops the debug session from this same event —
     * so what is decided here is known by the time anything asks.
     *
     * `willBeDestroyed` cannot tell the two ends apart: `ProcessHandler.notifyProcessTerminated`
     * passes `true` for a process that exited by itself as well. What does is the process: a
     * `destroyProcess` fires this *before* it kills anything, and an exit fires it *after* the
     * process is gone. So a process that is no longer alive here ended on its own, and its exit value
     * is already there to read.
     */
    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
        val process = (event.processHandler as? BaseOSProcessHandler)?.process ?: return
        if (!process.isAlive) endedOnItsOwnWith = process.exitValue()
    }

    /** What was kept, trimmed; empty when the process said nothing. */
    fun text(): String = synchronized(lock) { kept.toString() }.trim()

    private companion object {
        /** Enough for `by`'s diagnostics about why it would not start a program, and no more. */
        const val LIMIT = 8_000
    }
}
