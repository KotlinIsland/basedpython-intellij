package dev.basedpython.pycharm.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
 * equivalent for any of them. Nothing here needs `fileOpened` any more: a request to `by` names the
 * text it is about and is answered whether or not the platform has opened the document — see
 * [ByTextHash].
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
   * server's name, through [ByLspLifecycle].
   *
   * Both callbacks run on a background thread without a read lock (the platform annotates them
   * `@RequiresBackgroundThread` / `@RequiresReadLockAbsence`), and the publish runs on another one,
   * so a subscriber that needs the EDT or a read action must ask for one.
   */
  class Broadcaster(private val lifecycle: ByLspLifecycle, private val serverName: String) : LspServerListener {

    override fun serverInitialized(params: InitializeResult) {
      lifecycle.publish { it.serverInitialized(serverName) }
    }

    override fun serverStopped(shutdownNormally: Boolean) {
      lifecycle.publish { it.serverStopped(serverName, shutdownNormally) }
    }
  }
}

/**
 * Where [ByLspLifecycleListener.Broadcaster] publishes from: one coroutine of the plugin's, which
 * delivers the events in the order the servers reported them.
 *
 * Not from the callback itself, because the platform calls `serverStopped` *after* the plugin is
 * unloaded: removing the plugin's integration providers is what stops its servers, and they stop on
 * their own threads, after the platform has cleared the message buses' publisher caches. A publish
 * then put [ByLspLifecycleListener.TOPIC]'s publisher back into the project's bus, which held the
 * plugin's class loader for as long as the project was open (measured in unpinned PyCharm 263.7355).
 * The plugin's coroutines are
 * cancelled and joined before any of its extensions is removed, so a publish from here either
 * finished before the caches were cleared or never starts.
 */
@Service(Service.Level.PROJECT)
internal class ByLspLifecycle(private val project: Project, scope: CoroutineScope) {

  private val events = Channel<(ByLspLifecycleListener) -> Unit>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (event in events) {
        if (!project.isDisposed) event(project.messageBus.syncPublisher(ByLspLifecycleListener.TOPIC))
      }
    }
  }

  /** Queues [event] for [ByLspLifecycleListener.TOPIC]'s subscribers; nothing, once the plugin's scope has ended. */
  fun publish(event: (ByLspLifecycleListener) -> Unit) {
    events.trySend(event)
  }

  companion object {
    fun getInstance(project: Project): ByLspLifecycle = project.service()
  }
}
