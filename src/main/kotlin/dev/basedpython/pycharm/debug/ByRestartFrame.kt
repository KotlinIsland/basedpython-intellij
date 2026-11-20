package dev.basedpython.pycharm.debug

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.util.ThreeState
import com.intellij.xdebugger.frame.XDropFrameHandler
import com.intellij.xdebugger.frame.XStackFrame
import com.jetbrains.dap.protocol.DapServer
import com.jetbrains.dap.protocol.FrameId
import com.jetbrains.dap.protocol.RestartFrameArguments
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException

/**
 * Reset Frame in a basedpython session: the platform's, with bpd's refusal shown as bpd wrote it.
 *
 * Which frames offer it is still the platform's decision ([canDropFrame] is its own), made off the
 * `canRestart` bpd sends on each frame of a stack. What changes is the request. The platform sends
 * it and leaves a refusal to its generic adapter-error path, which hands the sentence to a
 * notification as HTML: `<module>` vanished as a tag, and bpd's backticks showed as characters.
 * Sent here, the refusal is caught before that path sees it and rendered by [ByBpdProse].
 */
internal class ByDropFrameHandler(
    private val platform: XDropFrameHandler,
    private val project: Project,
) : XDropFrameHandler {

    private val requests = ByRestartRequests()

    override fun canDropFrame(frame: XStackFrame): ThreeState = platform.canDropFrame(frame)

    /**
     * Sent on the frame's own executor, which is the pause the frame was read in — so a reset asked
     * for just as the thread resumes is dropped with the pause, as the platform's is.
     */
    override fun drop(frame: XStackFrame) {
        val dap = frame as? DefaultDapXStackFrame ?: return platform.drop(frame)
        dap.executor.post { requests.restart(server, dap.frame.id, ::tell) }
    }

    private fun tell(refusal: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                BasedPythonBundle.message("debug.restart.refused.title"),
                ByBpdProse.html(refusal),
                NotificationType.ERROR,
            )
            .notify(project)
    }

    private companion object {
        /** The group registered in `plugin.xml`, which every balloon of this plugin uses. */
        private const val NOTIFICATION_GROUP = "basedpython"
    }
}

/**
 * The `restartFrame` request and what the user is told when bpd will not make it — apart from any
 * session, so it can be driven against the platform's DAP client with a stand-in adapter.
 */
internal class ByRestartRequests {

    /**
     * Restarts [frame], or hands bpd's own sentence to [refused]. A failure that is not the adapter
     * refusing — a closed connection, a cancelled pause — is left to the platform, which logs it.
     */
    suspend fun restart(server: DapServer, frame: FrameId, refused: (String) -> Unit) {
        try {
            server.restartFrame(RestartFrameArguments(frameId = frame))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            refused(ByDapRequests.refusalOf(e) ?: throw e)
        }
    }
}
