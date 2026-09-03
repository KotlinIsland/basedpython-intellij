package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.hotswap.SourceFileChangesCollector
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Which files have been edited since the running program was last brought up to date.
 *
 * This is what puts the platform's hot reload toolbar on screen and takes it away again: the
 * platform polls [getChanges] and shows the bar while the set is non-empty, and calls
 * [resetChanges] once a reload has been applied.
 *
 * ## why this is ours
 *
 * The platform ships exactly one implementation of the public [SourceFileChangesCollector] —
 * `SourceFileChangesCollectorImpl` — and it lives in `com.intellij.xdebugger.impl.hotswap`, is
 * `@ApiStatus.Internal`, and has no public factory. Borrowing it also meant borrowing its shape: its
 * constructor changed between 262 and 263, so it had to be built by reflective lookup or the plugin
 * was a `NoSuchMethodError` on half its declared range — and that error came out of
 * `HotSwapSessionImpl.init`, which runs while the debug session starts, so getting it wrong cost the
 * whole session rather than just hot reload. See docs/internal-api.md.
 *
 * The interface it implements is three methods. Writing them is smaller than the reflection was, is
 * the same on every build, and cannot fail at session start.
 *
 * ## how a change is decided
 *
 * By content, not by edit count, so that **typing something and then undoing it puts the toolbar
 * away again** — which is the behaviour the platform's implementation was wanted for. The first time
 * a document changes after a reset, the text it had *before* that change is kept: that is precisely
 * its content at the last reset, since this is the first change since. A file counts as changed
 * while its current text differs from that snapshot, so reverting an edit — by undo, by retyping, by
 * anything — makes it stop counting.
 *
 * One difference from the platform's, stated rather than hidden: this watches **documents**, where
 * the platform's also consults local history, so a file changed on disk behind the IDE's back is not
 * noticed until the change reaches a document. Every route this plugin's hot reload actually takes
 * goes through an editor, and [ByHotSwapProvider] saves documents before it asks `by` for anything.
 *
 * Not thread-confined: the platform polls [getChanges] from its own coroutines while document
 * listeners fire on the EDT, so the maps are concurrent.
 */
internal class ByChangesCollector(
  private val listener: SourceFileChangesListener,
  /** Which files are worth watching — extension and project membership, decided by the caller. */
  private val watches: (VirtualFile) -> Boolean,
) : SourceFileChangesCollector<VirtualFile> {

  /** File to the text it held at the last reset, for every file touched since. */
  private val snapshots = ConcurrentHashMap<VirtualFile, CharSequence>()

  /** The set the platform is currently being told about, kept to spot the empty/non-empty edges. */
  private val changed = ConcurrentHashMap.newKeySet<VirtualFile>()

  private val documentListener = object : DocumentListener {
    /**
     * Before, not after: this is the only moment the pre-edit text is still readable, and for the
     * first change since a reset that text *is* the file's content at the reset.
     */
    override fun beforeDocumentChange(event: DocumentEvent) {
      val file = fileOf(event.document) ?: return
      snapshots.computeIfAbsent(file) { event.document.immutableCharSequence }
    }

    override fun documentChanged(event: DocumentEvent) {
      val file = fileOf(event.document) ?: return
      val snapshot = snapshots[file] ?: return
      val wasEmpty = changed.isEmpty()
      // `contentEquals` rather than identity: the document rebuilds its sequence on every edit.
      if (event.document.immutableCharSequence.contentEquals(snapshot)) {
        // Back to where it started. Drop the snapshot too, so the next edit takes a fresh one.
        changed.remove(file)
        snapshots.remove(file)
        if (changed.isEmpty() && !wasEmpty) listener.onChangesCanceled()
      } else if (changed.add(file) && wasEmpty) {
        listener.onNewChanges()
      }
    }
  }

  init {
    // The application-wide multicaster, disposed with this collector: the platform creates one of
    // these per hot swap session and disposes it with the session.
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
  }

  private fun fileOf(document: Document): VirtualFile? =
    FileDocumentManager.getInstance().getFile(document)?.takeIf { it.isValid && watches(it) }

  override fun getChanges(): Set<VirtualFile> = changed.toSet()

  override fun resetChanges() {
    changed.clear()
    snapshots.clear()
  }

  override fun dispose() {
    // The listener is registered against this Disposable, so the multicaster lets go of it here.
    Disposer.dispose(this, false)
    changed.clear()
    snapshots.clear()
  }
}
