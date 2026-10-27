package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler

/**
 * `by`'s own process, killed — tree and all — when the IDE ends it, unless what it ran has already
 * ended without it.
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
    private val finishesOnItsOwn: () -> Boolean,
) : KillableColoredProcessHandler(commandLine) {

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
