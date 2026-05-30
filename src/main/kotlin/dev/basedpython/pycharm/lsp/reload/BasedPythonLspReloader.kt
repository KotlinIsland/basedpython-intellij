package dev.basedpython.pycharm.lsp.reload

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.util.Alarm
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project service that polishes the BasedPython LSP lifecycle:
 *
 *  1. **Restart-on-settings-change** — [onSettingsChanged] debounces (~1s) and then
 *     restarts both servers via the same [LspServerManager.stopAndRestartIfNeeded] call
 *     used by the manual "Restart BasedPython LSP Servers" action.
 *  2. **Crash recovery** — subscribes to [LspServerManagerListener] and, when a server
 *     reaches [LspServerState.ShutdownUnexpectedly], posts a `BasedPython.Actions`
 *     notification with a one-click "Restart" action.
 *
 * Created via [getInstance]; lifecycle activation (listener registration) is performed by
 * [BasedPythonLspReloadActivity] on project open.
 */
@Service(Service.Level.PROJECT)
internal class BasedPythonLspReloader(private val project: Project) : Disposable {

  private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val listenerRegistered = AtomicBoolean(false)

  /** Provider classes whose servers we own. Restarting these is idempotent. */
  private val providers: List<Class<out LspServerSupportProvider>> = listOf(
    ByLspServerSupportProvider::class.java,
    BuffLspServerSupportProvider::class.java,
  )

  /** De-dupes crash notifications: one balloon per server-restart cycle. */
  private val notifiedCrash = ConcurrentHashMap.newKeySet<String>()

  /**
   * Called when BasedPython settings (binary paths, args, toggles) change. Debounces with
   * a ~1s [Alarm] so a flurry of edits collapses into a single restart, then restarts the
   * servers so the new settings take effect without a manual restart.
   */
  fun onSettingsChanged() {
    if (project.isDisposed) return
    alarm.cancelAllRequests()
    alarm.addRequest({ restartNow() }, DEBOUNCE_MS)
  }

  /** Immediately restart both servers (used by the debounce callback and crash "Restart"). */
  fun restartNow() {
    if (project.isDisposed) return
    val mgr = LspServerManager.getInstance(project)
    for (provider in providers) {
      runCatching { mgr.stopAndRestartIfNeeded(provider) }
        .onFailure { LOG.warn("Failed to restart LSP server for ${provider.simpleName}", it) }
    }
  }

  /**
   * Registers the crash-recovery listener on the project's [LspServerManager]. Idempotent.
   * Called once on project open by [BasedPythonLspReloadActivity].
   */
  fun ensureListenerRegistered() {
    if (project.isDisposed) return
    if (!listenerRegistered.compareAndSet(false, true)) return
    val mgr = LspServerManager.getInstance(project)
    mgr.addLspServerManagerListener(CrashListener(), this, /* sendOldStateOnAdd = */ true)
  }

  private fun ownsServer(server: LspServer): Boolean = providers.any { it == server.providerClass }

  private fun serverKey(server: LspServer): String =
    "${server.providerClass.name}@${System.identityHashCode(server)}"

  private inner class CrashListener : LspServerManagerListener {
    override fun serverStateChanged(lspServer: LspServer) {
      if (!ownsServer(lspServer)) return
      val key = serverKey(lspServer)
      when (lspServer.state) {
        LspServerState.ShutdownUnexpectedly -> {
          if (notifiedCrash.add(key)) notifyCrash(lspServer)
        }
        // A fresh start clears the de-dupe latch so a future crash notifies again.
        LspServerState.Initializing, LspServerState.Running -> notifiedCrash.remove(key)
        else -> {}
      }
    }
  }

  private fun notifyCrash(server: LspServer) {
    if (project.isDisposed) return
    val name = serverDisplayName(server)
    ApplicationManager.getApplication().invokeLater(
      {
        if (project.isDisposed) return@invokeLater
        val notification = NotificationGroupManager.getInstance()
          .getNotificationGroup(GROUP_ID)
          .createNotification(
            BasedPythonBundle.message("notification.lspCrashed.title", name),
            BasedPythonBundle.message("notification.lspCrashed.content"),
            NotificationType.ERROR,
          )
        notification.addAction(
          com.intellij.notification.NotificationAction.createSimple(BasedPythonBundle.message("notification.action.restart")) {
            notification.expire()
            restartNow()
          },
        )
        notification.notify(project)
      },
      project.disposed,
    )
  }

  /** Human-readable server name derived from the provider class. */
  private fun serverDisplayName(server: LspServer): String = when (server.providerClass) {
    ByLspServerSupportProvider::class.java -> "by"
    BuffLspServerSupportProvider::class.java -> "buff"
    else -> server.providerClass.simpleName
  }

  override fun dispose() {
    Disposer.dispose(alarm)
    notifiedCrash.clear()
  }

  companion object {
    private const val GROUP_ID = "BasedPython.Actions"
    private const val DEBOUNCE_MS = 1000

    @JvmStatic
    fun getInstance(project: Project): BasedPythonLspReloader = project.service()
  }
}

private val LOG = Logger.getInstance(BasedPythonLspReloader::class.java)
