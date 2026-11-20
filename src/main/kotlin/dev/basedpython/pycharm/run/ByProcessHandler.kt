package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.openapi.util.SystemInfo

/**
 * `by`'s own process, ended with a SIGTERM to it alone when the IDE ends it — which `by run`
 * passes on to the program — unless what it ran has already ended without it.
 *
 * A debug session hands the platform this process as the one it debugs
 * ([dev.basedpython.pycharm.debug.ByDapXDebugProcess]), and the platform ends a debug session's
 * process whenever the session ends: on Stop, and also when the adapter ends the session itself
 * because the program has finished. In the second case the program is gone and `by run` is only on
 * its way out behind it — it exits with the program's own code a moment later — so killing it there
 * turned every finished debug run into "exit code 137 (SIGKILL)". [finishesOnItsOwn] says which case
 * a request is.
 *
 * It is asked as the request is made, before the handler announces its end, because that
 * announcement is what starts the debug session's own stop on Stop: asked any later, a Stop could
 * find the session it had just stopped and read itself as the adapter's end.
 *
 * A process left to finish is terminating rather than gone, so a second Stop kills it, as the
 * platform's Stop does for any process that is slow to end.
 */
internal class ByProcessHandler(
    commandLine: GeneralCommandLine,
    /**
     * Whether a SIGTERM to `by` reaches the program; when it does not, the first Stop destroys the
     * tree, as it did before `by run` passed one on. See [ByCommandLineState.signalReachesProgram].
     */
    private val signalReachesProgram: () -> Boolean = { true },
    private val finishesOnItsOwn: () -> Boolean,
) : KillableColoredProcessHandler(commandLine) {

    init {
        setShouldDestroyProcessRecursively(true)
    }

    /**
     * Asked as Stop ends the process, not when it starts: under a debugger whether the signal reaches
     * the program is the adapter's answer, given once it has started. Windows has no signals: there
     * `by` alone would be terminated and its interpreter left running, so the first Stop destroys
     * the tree, as it did everywhere before `by run` passed a signal on.
     */
    override fun shouldKillProcessSoftly(): Boolean = signalReachesProgram() && !SystemInfo.isWindows

    /**
     * Stop: a SIGTERM to `by` alone, which `by run` passes on to the program it started before
     * exiting with the program's status — so the program's own handler runs, once, and the exit
     * code is the program's. A program that does not end on it is killed, tree and all, by the
     * second Stop, which is [killProcess].
     *
     * Measured with a program that counts the signals it gets and prints as it ends: before, the
     * soft kill was off and the tree destroyed, a SIGKILL to every process, and the program heard
     * nothing and ended in 137. The platform's own soft kill is a SIGINT to every process of the
     * tree, which would reach the program twice, once from `by` passing it on. And `Process.destroy`,
     * the platform's default for one process, sends the SIGTERM but then closes the pipes the
     * process writes to, so whatever it printed on the way out was lost and the next write killed
     * it with SIGPIPE (exit 141). This sends the signal and leaves the pipes to close when the
     * process has ended.
     *
     * What ran below `by` as the signal was sent and is still running once `by` has ended gets a
     * SIGTERM of its own then, see [stoppedTree]: `by` passes the signal to the program alone, so a
     * process the program started and did not end itself outlives both, holding the output pipe it
     * inherited, and the handler, whose reader blocks on that pipe until its last writer closes it,
     * never announced the end. Measured with the JDK's own pipes, on Linux and macOS alike: a read
     * in flight as the process died stayed blocked while an orphaned `sleep` lived and returned the
     * end once it was signalled. Nor could the second Stop reach it: the platform kills the tree
     * below the process by parent, and an orphan's parent is no longer `by`.
     */
    override fun destroyProcessGracefully(): Boolean {
        val by = ProcessHandle.of(process.pid()).orElse(null) ?: return false
        stoppedTree = by.descendants().toList()
        if (!by.destroy()) return false
        process.onExit().thenRun { survivors().forEach(ProcessHandle::destroy) }
        return true
    }

    /**
     * Every process below `by` when Stop sent it the signal: those `by` leaves behind when it ends
     * are what [destroyProcessGracefully] signals then and [killProcess] kills. Taken as the signal
     * is sent because once `by` has ended its orphans are no longer found below it.
     */
    @Volatile
    private var stoppedTree: List<ProcessHandle> = emptyList()

    /** Those of [stoppedTree] still running; a [ProcessHandle] knows its process from a reused pid. */
    private fun survivors(): List<ProcessHandle> = stoppedTree.filter(ProcessHandle::isAlive)

    /**
     * The second Stop: the platform's kill of the tree below `by`, and of whatever `by` left behind
     * when the first Stop ended it, with what each of those has started since.
     */
    override fun killProcess() {
        super.killProcess()
        survivors().flatMap { listOf(it) + it.descendants().toList() }.forEach(ProcessHandle::destroyForcibly)
    }

    /** Set, and never cleared, once a request to end the process came after what it ran had ended. */
    @Volatile
    private var leftToFinish = false

    override fun destroyProcess() {
        if (finishesOnItsOwn()) leftToFinish = true
        super.destroyProcess()
    }

    override fun doDestroyProcess() {
        if (!leftToFinish) super.doDestroyProcess()
    }
}
