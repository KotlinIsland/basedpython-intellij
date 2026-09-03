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
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.util.Alarm
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project service that polishes the basedpython LSP lifecycle:
 *
 *  1. **Restart-on-settings-change** — [onSettingsChanged] debounces (~1s) and then
 *     restarts both servers via the same [LspServerManager.stopAndRestartIfNeeded] call
 *     used by the manual "Restart basedpython LSP Servers" action.
 *  2. **Crash recovery** — subscribes to [ByLspLifecycleListener] and, when a server stops
 *     without having been asked to, posts a `basedpython.Actions` notification with a
 *     one-click "Restart" action.
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
   * Called when basedpython settings (binary paths, args, toggles) change. Debounces with
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
   * Subscribes to [ByLspLifecycleListener] for crash recovery. Idempotent. Called once on project
   * open by [BasedPythonLspReloadActivity].
   *
   * Was `LspServerManager.addLspServerManagerListener`, which is `@ApiStatus.Internal` — see
   * [ByLspLifecycleListener] for the public route this takes instead. Only servers this plugin
   * owns publish on that topic, so the provider-class filter the manager listener needed is gone.
   */
  fun ensureListenerRegistered() {
    if (project.isDisposed) return
    if (!listenerRegistered.compareAndSet(false, true)) return
    project.messageBus.connect(this).subscribe(ByLspLifecycleListener.TOPIC, CrashListener())
  }

  private inner class CrashListener : ByLspLifecycleListener {
    override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
      // `shutdownNormally == false` is the platform's LspServerState.ShutdownUnexpectedly.
      if (shutdownNormally) return
      if (notifiedCrash.add(serverName)) notifyCrash(serverName)
    }

    /** A fresh start clears the de-dupe latch so a future crash notifies again. */
    override fun serverInitialized(serverName: String) {
      notifiedCrash.remove(serverName)
    }
  }

  private fun notifyCrash(name: String) {
    if (project.isDisposed) return
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

  override fun dispose() {
    Disposer.dispose(alarm)
    notifiedCrash.clear()
  }

  companion object {
    private const val GROUP_ID = "basedpython.Actions"
    private const val DEBOUNCE_MS = 1000

    @JvmStatic
    fun getInstance(project: Project): BasedPythonLspReloader = project.service()
  }
}

private val LOG = Logger.getInstance(BasedPythonLspReloader::class.java)
