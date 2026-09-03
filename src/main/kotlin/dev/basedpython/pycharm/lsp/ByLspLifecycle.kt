package dev.basedpython.pycharm.lsp

import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.util.messages.Topic
import org.eclipse.lsp4j.InitializeResult

/**
 * When a basedpython language server starts and stops, for the parts of the plugin that care.
 *
 * The platform has a manager-level listener for this — `LspServerManagerListener`, and the
 * `LspClientManagerListener` it was renamed to — and both are `@ApiStatus.Internal`, which is what
 * JetBrains Marketplace declined this plugin for. What is *not* internal is
 * [LspServerListener]: two methods, handed to the platform by the descriptor itself through
 * `LspClientDescriptor.lspServerListener`, which is the supported way for the owner of a server to
 * hear about its own server.
 *
 * That listener is per-descriptor, so it says nothing about *which* server stopped — it does not
 * need to, because the descriptor that installed it already knows. [Broadcaster] carries that name
 * across, and the events reach the rest of the plugin on this topic. Which is the better shape
 * anyway: the manager listener was a firehose of every server in the project that each subscriber
 * then filtered by provider class, and the two subscribers here only ever wanted their own.
 *
 * Deliberately narrower than what it replaces. The manager listener also reported `fileOpened`,
 * `fileEdited`, `diagnosticsReceived` and `documentLinksReceived`, and the public listener has no
 * equivalent for any of them — see `docs.render.ByRenderedDocsRefresher`, which still needs one.
 */
internal interface ByLspLifecycleListener {

  /** The server named [serverName] finished initializing and is ready for requests. */
  fun serverInitialized(serverName: String) {}

  /**
   * The server named [serverName] stopped. [shutdownNormally] is false when it died on its own —
   * the platform's `LspServerState.ShutdownUnexpectedly`, and the crash this plugin offers to
   * recover from.
   */
  fun serverStopped(serverName: String, shutdownNormally: Boolean) {}

  companion object {
    @Topic.ProjectLevel
    val TOPIC: Topic<ByLspLifecycleListener> =
      Topic(ByLspLifecycleListener::class.java, Topic.BroadcastDirection.NONE)
  }

  /**
   * The [LspServerListener] a descriptor hands the platform, republishing onto [TOPIC] under the
   * server's name.
   *
   * Both callbacks run on a background thread without a read lock (the platform annotates them
   * `@RequiresBackgroundThread` / `@RequiresReadLockAbsence`), and a synchronous publish keeps them
   * on it — so a subscriber that needs the EDT or a read action must ask for one, as both here do.
   */
  class Broadcaster(private val project: Project, private val serverName: String) : LspServerListener {

    override fun serverInitialized(params: InitializeResult) {
      if (project.isDisposed) return
      project.messageBus.syncPublisher(TOPIC).serverInitialized(serverName)
    }

    override fun serverStopped(shutdownNormally: Boolean) {
      if (project.isDisposed) return
      project.messageBus.syncPublisher(TOPIC).serverStopped(serverName, shutdownNormally)
    }
  }
}
