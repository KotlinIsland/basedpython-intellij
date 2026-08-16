package dev.basedpython.pycharm.format

import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

private val LOG = Logger.getInstance(ByCleanup::class.java)

/**
 * A pass `buff` can run over a document before it is saved or committed.
 *
 * Each one is a source action the server already knows how to build, so the work — which rules
 * apply, in what order, against which configuration — stays on the server side, where it is the
 * same code that answers the editor.
 */
enum class ByCleanupOp(val kind: String, private val progressKey: String) {
  /**
   * Every fix the project's lint configuration asks for. Does not format, and does not sort
   * imports unless the project selected the `I` rules.
   */
  FixAll("source.fixAll.ruff", "progress.cleanup.fixAll"),

  /**
   * Sorts imports and drops the ones nothing uses — what *Optimize Imports* means, and more than
   * `source.organizeImports`, which only sorts. Not offered on save or commit, where the pass below
   * covers it; this is what Ctrl+Alt+O runs.
   */
  OptimizeImports("source.optimizeImports.ruff", "progress.optimizeImports"),

  /**
   * Sorts imports, drops the ones nothing uses, and formats what that left behind — all as one
   * edit computed against one buffer.
   */
  FormatAndOptimizeImports("source.formatAndOptimizeImports.ruff", "progress.cleanup.format"),
  ;

  val progressText: String get() = BasedPythonBundle.message(progressKey)

  companion object {
    /**
     * The order the passes run in, which is fixed rather than the user's to choose.
     *
     * The lint pass rewrites code and the formatter lays out whatever it left, so formatting is
     * last. The other way round leaves a file that has just been formatted and then edited.
     */
    fun inRunOrder(ops: Collection<ByCleanupOp>): List<ByCleanupOp> =
      entries.filter { it in ops }
  }
}

/**
 * The passes a user can switch on for save and for commit.
 *
 * A subset of [ByCleanupOp]: [ByCleanupOp.OptimizeImports] is reachable through *Optimize Imports*
 * but is not offered here, because [ByCleanupOp.FormatAndOptimizeImports] already does it and
 * having both would be two ways to ask for the same thing.
 */
enum class ByCleanupToggle(val op: ByCleanupOp) {
  FormatAndOptimizeImports(ByCleanupOp.FormatAndOptimizeImports),
  FixAll(ByCleanupOp.FixAll),
}

/**
 * Runs [ByCleanupOp]s against a document by asking `buff` for the edit and applying it here.
 *
 * The edit is *pulled* rather than pushed. `buff` also exposes each pass as a
 * `workspace/executeCommand`, but a command delivers its result by asking the client to apply a
 * workspace edit, and the platform does that asynchronously — the response to the command arrives
 * first. An action on save has to be finished by the time it returns, because the platform saves
 * the document immediately afterwards, so it cannot use a command. A code action hands back the
 * edit, which is what this applies.
 */
object ByCleanup {

  /**
   * Applies each of [ops] to [document] in turn, and reports whether anything changed.
   *
   * Each pass is a separate request, and that is safe because the platform sends `didChange`
   * synchronously while the document is being mutated: by the time one pass's edit has been
   * applied, the server is already answering from the new text. So the second pass sees the first
   * pass's output rather than the original.
   */
  suspend fun run(
    project: Project,
    file: VirtualFile,
    document: Document,
    ops: Collection<ByCleanupOp>,
  ): Boolean {
    val server = findServer(project, file) ?: run {
      LOG.debug("No running buff server for ${file.path} — skipping cleanup")
      return false
    }

    var changed = false
    for (op in ByCleanupOp.inRunOrder(ops)) {
      val edits = requestEdits(server, file, op) ?: continue
      if (edits.isEmpty()) continue
      writeCommandAction(project, op.progressText) {
        applyEditsTo(document, edits)
      }
      changed = true
    }
    return changed
  }

  /** The `buff` server serving [file], if one is running. */
  fun findServer(project: Project, file: VirtualFile): LspServer? =
    LspServerManager.getInstance(project)
      .getServersForProvider(BuffLspServerSupportProvider::class.java)
      .firstOrNull { it.descriptor.isSupportedFile(file) }

  /**
   * Asks the server for [op]'s edit for [file].
   *
   * Returns null when the server could not answer at all — it is not initialized, timed out, or
   * replied with an error — which is different from an empty list, meaning there was nothing to do.
   */
  fun requestEdits(server: LspServer, file: VirtualFile, op: ByCleanupOp): List<TextEdit>? {
    val params = CodeActionParams(
      server.getDocumentIdentifier(file),
      // The whole file. These are source actions, so the range is not what selects them; `only` is.
      Range(Position(0, 0), Position(0, 0)),
      CodeActionContext(emptyList(), listOf(op.kind)),
    )

    val actions = server.sendRequestSync { it.textDocumentService.codeAction(params) } ?: return null

    // `buff` answers a named source action with exactly that action, so anything else means the
    // server does not know this kind — an older binary, most likely.
    val action = actions.firstNotNullOfOrNull { it.takeIf { it.isRight }?.right } ?: run {
      LOG.debug("buff returned no `${op.kind}` action for ${file.path}")
      return emptyList()
    }

    val resolved = if (action.edit == null) {
      server.sendRequestSync { it.textDocumentService.resolveCodeAction(action) } ?: return null
    } else {
      action
    }

    return editsFor(resolved, server, file)
  }

  /** Pulls out the edits [resolved] makes to [file] itself, ignoring any it makes elsewhere. */
  private fun editsFor(resolved: CodeAction, server: LspServer, file: VirtualFile): List<TextEdit> {
    val changes = resolved.edit?.changes ?: return emptyList()
    val uri = server.getDocumentIdentifier(file).uri
    return changes[uri].orEmpty()
  }

  /**
   * Applies [edits] to [document].
   *
   * Applied last-first so that an earlier edit's offsets are still valid when it is its turn —
   * every edit's range refers to the document as it was when the server computed them.
   */
  fun applyEditsTo(document: Document, edits: List<TextEdit>) {
    edits
      .sortedByDescending { document.offsetOf(it.range.start) }
      .forEach { edit ->
        document.replaceString(
          document.offsetOf(edit.range.start),
          document.offsetOf(edit.range.end),
          edit.newText,
        )
      }
  }

  /**
   * Where [position] falls in [document].
   *
   * An LSP character is a UTF-16 code unit, which is what a [Document] offset counts too — the
   * platform advertises no `positionEncodings`, so the server falls back to UTF-16. A range that
   * ends one line past the last is how a server says "to the end of the file".
   */
  private fun Document.offsetOf(position: Position): Int {
    if (position.line >= lineCount) return textLength
    val line = position.line.coerceAtLeast(0)
    return (getLineStartOffset(line) + position.character.coerceAtLeast(0))
      .coerceAtMost(getLineEndOffset(line))
  }
}

/** Reads the file behind [document], under a read action. */
internal suspend fun fileOf(document: Document): VirtualFile? = readAction {
  com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
}
