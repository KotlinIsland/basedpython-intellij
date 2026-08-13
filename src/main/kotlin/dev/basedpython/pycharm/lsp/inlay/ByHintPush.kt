package dev.basedpython.pycharm.lsp.inlay

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import java.awt.AWTEvent
import java.awt.event.InputEvent
import java.awt.event.WindowEvent
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which modifiers are held down right now, for the hints that are only drawn while one is
 * ([ByHintMode.ON_PUSH]).
 *
 * IDE-wide, because the key is: it is held over whatever editor has focus, and both a split and a
 * second project window should light up together. Which combination counts is still per project —
 * every watcher is asked with its own [ByPushKey], so this only has to publish the raw state.
 *
 * **Why an event dispatcher rather than a keymap action.** A push is a key being *held*: it starts
 * on the press and ends on the release, and the action system only ever tells you about the press.
 * A dispatcher sees both, and reads the state off [InputEvent.getModifiersEx] rather than tracking
 * presses itself, so a modifier that goes down or up while the IDE is not looking cannot leave the
 * state stuck. It never consumes an event — `dispatch` always returns false — so holding the key
 * still does whatever else it does.
 *
 * Installed on the first [watch], so a project with no push-mode hints never registers anything.
 *
 * Watchers are held **weakly**: a presentation lives as long as its inlay, which is not something
 * this can be told about, and a stale watcher would otherwise pin an editor's worth of them.
 */
@Service(Service.Level.APP)
class ByHintPush : Disposable {

    /** Something that wants to hear when the held modifiers change. */
    interface Watcher {
        /** The editor it draws in, so one editor's worth of them can be updated in one go. */
        val editor: Editor

        fun pushStateChanged()
    }

    /** Last seen [InputEvent.getModifiersEx], masked to [ByPushKey.WATCHED_MODIFIERS]. */
    @Volatile
    private var modifiers: Int = 0

    private val watchers: MutableSet<Watcher> = Collections.newSetFromMap(WeakHashMap())

    private val installed = AtomicBoolean(false)

    private val dispatcher = IdeEventQueue.EventDispatcher { event ->
        onEvent(event)
        false
    }

    /** Whether [key] is held down at this moment. */
    fun isHeld(key: ByPushKey): Boolean = key.isHeldIn(modifiers)

    /**
     * Registers [watcher] to hear about changes, weakly, and starts watching if nothing else has.
     *
     * Safe to call from the daemon's background thread, which is where presentations are built: the
     * dispatcher itself is registered on the EDT.
     */
    fun watch(watcher: Watcher) {
        synchronized(watchers) { watchers.add(watcher) }
        install()
    }

    private fun install() {
        if (!installed.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            IdeEventQueue.getInstance().addDispatcher(dispatcher, this)
        }
    }

    /**
     * Every input event carries the modifier state at the time it happened, so keys and mouse alike
     * are read the same way and nothing has to be inferred from press/release pairs.
     *
     * A window losing focus clears the state outright. Holding the key and switching away with
     * Cmd+Tab releases it somewhere this will never hear about, and hints left showing from a push
     * nobody is making any more are worse than a peek that ends early.
     *
     * Internal rather than private only so a test can play events at it; [dispatcher] is the one
     * caller in production.
     */
    internal fun onEvent(event: AWTEvent) {
        val next = when {
            event is InputEvent -> event.modifiersEx and ByPushKey.WATCHED_MODIFIERS
            event is WindowEvent && event.id == WindowEvent.WINDOW_DEACTIVATED -> 0
            else -> return
        }
        if (next == modifiers) return
        modifiers = next
        notifyWatchers()
    }

    /**
     * Tells every watcher, an editor at a time.
     *
     * On the EDT, which is where the inlays being resized want it, and in batches, which is what
     * keeps a keypress cheap: one press changes the width of every push hint in the file at once,
     * and `InlayModel.execute(batchMode = true)` is how the editor is told to lay out and repaint
     * once for the lot rather than per inlay.
     */
    private fun notifyWatchers() {
        val current = synchronized(watchers) { watchers.toList() }
        for ((editor, group) in current.groupBy { it.editor }) {
            if (editor.isDisposed) continue
            editor.inlayModel.execute(true) { group.forEach { it.pushStateChanged() } }
        }
    }

    override fun dispose() {
        synchronized(watchers) { watchers.clear() }
    }

    companion object {
        @JvmStatic
        fun getInstance(): ByHintPush = ApplicationManager.getApplication().service()
    }
}
