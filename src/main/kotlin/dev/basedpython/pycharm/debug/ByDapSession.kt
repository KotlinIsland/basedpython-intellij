package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapThreadState
import com.intellij.platform.dap.DapThreadsListState
import com.intellij.platform.dap.xdebugger.DapXSuspendContext
import com.intellij.xdebugger.XDebugSession

/**
 * Which thread a change in the thread list is about, or null when it is about none.
 *
 * The platform's own reading, kept exactly: the thread the session says it stopped at, and failing
 * that the first one that is paused. Reproduced rather than improved because the disagreement this
 * file exists over is what to *do* with the answer, not how to find it — and a second reading of the
 * same state would be a second thing to keep in step.
 */
internal fun DapThreadsListState.stoppedThread() =
    stoppedAtThread ?: threads.firstOrNull { it.state is DapThreadState.Paused }

/**
 * What to do with a suspension while the session is already suspended.
 *
 * ## the bug this exists for
 *
 * `DapXDebugProcess`'s own listener asks one question — `XDebugSession.isSuspended` — and queues the
 * new context whenever the answer is yes, to be shown on the next Resume rather than now. For a
 * *second thread* stopping while you read the first, that is right: an adapter with
 * `supportsSingleThreadExecutionRequests` (bpd is one) can suspend a thread at any moment, and
 * having the editor jump away from what you are reading would be worse than a delay.
 *
 * It is wrong for the thread you are already on. DAP prescribes a `stopped` event after
 * `restartFrame` and after `goto` — response first, then `stopped` with reason `restart` or `goto`
 * — and that event does not mean "this thread stopped", it means "this thread is somewhere else
 * now". Queued, the editor's highlight stays where the code no longer is, and because the platform
 * drains that queue only in `resume`, the user's next Resume shows the stale position **instead of
 * resuming the program**. Both were established from `intellij.platform.dap` itself; see
 * `scratch.ij-dap-issues.md` §3.
 *
 * ## the rule
 *
 * Apply when the suspension is about the thread the user is looking at. Queue only a genuinely
 * different thread's.
 *
 * Deliberately about the *thread* rather than about the event's `reason`. Reason would work for the
 * two cases DAP names, and would silently be wrong for any other way an adapter reports that the
 * thread it is holding has moved — which is the shape of the bug being fixed, one level along.
 */
internal fun shouldApplyNow(
    suspended: Boolean,
    displayedThreadId: Int?,
    stoppedThreadId: Int?,
): Boolean = !suspended || displayedThreadId == null || displayedThreadId == stoppedThreadId

/** The thread whose stop the session is currently showing, or null when it is showing none. */
internal fun XDebugSession.displayedThreadId(): Int? =
    (suspendContext as? DapXSuspendContext)?.activeThread?.id
