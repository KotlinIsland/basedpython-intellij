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
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

private val LOG = Logger.getInstance(ByCleanup::class.java)

/**
 * A pass the formatter/linter server can run over a whole document.
 *
 * Each one is a source action the server already knows how to build, so the work — which rules
 * apply, in what order, against which configuration — stays on the server side, where it is the
 * same code that answers the editor.
 *
 * The three the IDE offers differ in what they are allowed to remove, which is the line the user
 * draws between them: reformatting may move an import but not delete one, optimizing imports may
 * delete the ones nothing uses, and the fix-all pass may rewrite anything the project's lint
 * configuration asks it to.
 */
enum class ByCleanupOp(val kind: String, private val progressKey: String) {
  /**
   * Every fix the project's lint configuration asks for. Does not format, and does not sort
   * imports unless the project selected the `I` rules. This is what save and commit offer.
   */
  FixAll("source.fixAll.ruff", "progress.cleanup.fixAll"),

  /**
   * Sorts imports and drops the ones nothing uses — what *Optimize Imports* means, and more than
   * `source.organizeImports`, which only sorts. This is what Ctrl+Alt+O runs, through
   * [BuffImportOptimizer].
   *
   * Not offered on save or commit: the platform's own *Optimize imports* row already covers it
   * there, and reaches this same pass.
   */
  OptimizeImports("source.optimizeImports.ruff", "progress.optimizeImports"),

  /**
   * Sorts a module's imports and formats what that left behind, as one edit against one buffer.
   * This is what *Reformat Code* runs, through [dev.basedpython.pycharm.editor.format.BuffFormattingService].
   *
   * Sorts without pruning, unlike [OptimizeImports]: laying a file out is not licence to delete
   * anything from it, and dropping the unused imports is asked for separately.
   */
  FormatAndOrganizeImports("source.formatAndOrganizeImports.ruff", "progress.cleanup.format"),
  ;

  val progressText: String get() = BasedPythonBundle.message(progressKey)
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

  /** Applies [op] to [document], and reports whether anything changed. */
  suspend fun run(
    project: Project,
    file: VirtualFile,
    document: Document,
    op: ByCleanupOp,
  ): Boolean {
    val server = findServer(project, file) ?: run {
      LOG.debug("No running buff server for ${file.path} — skipping cleanup")
      return false
    }

    val edits = requestEdits(server, file, op)
    if (edits.isNullOrEmpty()) return false

    writeCommandAction(project, op.progressText) {
      applyEditsTo(document, edits)
    }
    return true
  }

  /** The `buff` server serving [file], if one is running. */
  fun findServer(project: Project, file: VirtualFile): LspServer? =
    LspServerManager.getInstance(project)
      .getServersForProvider(BuffLspServerSupportProvider::class.java)
      .firstOrNull { it.descriptor.isSupportedFile(file) }

  /**
   * Whether [server] said at startup that it can answer [op].
   *
   * A server that cannot returns no action rather than an error, which is the same answer it gives
   * for "there was nothing to do" — so without asking this first, an older `buff` looks exactly
   * like a file that needed no changes. Answers true when the server has not reported its
   * capabilities yet, because not knowing is not the same as knowing it cannot.
   */
  fun advertises(server: LspServer, op: ByCleanupOp): Boolean {
    val kinds = server.initializeResult
      ?.capabilities
      ?.codeActionProvider
      ?.takeIf { it.isRight }
      ?.right
      ?.codeActionKinds
      ?: return true
    return op.kind in kinds
  }

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

    val edit = resolved.edit ?: return emptyList()
    return editsFor(edit, server.getDocumentIdentifier(file).uri)
  }

  /**
   * Pulls out the edits [edit] makes to [uri] itself, ignoring any it makes elsewhere.
   *
   * A workspace edit says the same thing in one of two shapes, and which one arrives is the
   * client's own doing: `documentChanges` when the client claimed to understand it, `changes`
   * otherwise. The platform claims it — it sets `WorkspaceEditCapabilities.documentChanges` — so
   * `buff` answers in `documentChanges` and leaves `changes` null, and reading only `changes` found
   * nothing to apply, ever. Both are read here, because that capability is the platform's to
   * promise and not this plugin's to rely on it keeping.
   */
  fun editsFor(edit: WorkspaceEdit, uri: String): List<TextEdit> {
    edit.changes?.get(uri)?.let { return it }

    // A `documentChanges` entry is either a text edit or a resource operation (create/rename/
    // delete a file); only the first is an edit to a document, and only these passes' own document
    // is this plugin's to apply.
    return edit.documentChanges.orEmpty()
      .mapNotNull { change -> change.takeIf { it.isLeft }?.left }
      .filter { it.textDocument.uri == uri }
      .flatMap { it.edits }
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
   * Applies [edits] to [text], and returns what that leaves.
   *
   * The [Document] overload is what the editor paths use. This one is for
   * [com.intellij.formatting.service.AsyncDocumentFormattingService], which is handed the text and
   * asks for the text back rather than for a document to mutate.
   */
  fun applyEditsTo(text: String, edits: List<TextEdit>): String {
    if (edits.isEmpty()) return text
    val lines = lineStartsOf(text)

    // Offsets are resolved against the original `text` throughout, which is what makes applying
    // them last-first correct: an edit's range describes the text as the server saw it, and every
    // edit still to come lies before the one just applied.
    return edits
      .sortedByDescending { text.offsetOf(it.range.start, lines) }
      .fold(StringBuilder(text)) { built, edit ->
        built.replace(
          text.offsetOf(edit.range.start, lines),
          text.offsetOf(edit.range.end, lines),
          edit.newText,
        )
      }
      .toString()
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

  /** The same as [Document.offsetOf], against a plain string. */
  private fun String.offsetOf(position: Position, lineStarts: IntArray): Int {
    if (position.line >= lineStarts.size) return length
    val line = position.line.coerceAtLeast(0)
    val lineEnd = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else length
    return (lineStarts[line] + position.character.coerceAtLeast(0)).coerceAtMost(lineEnd)
  }

  /**
   * Where each line of [text] begins.
   *
   * A document's line separator is always `\n` by the time the platform hands the text over — it
   * normalizes on load and restores the file's own separator on save — so this is the only one
   * there is to look for.
   */
  private fun lineStartsOf(text: String): IntArray {
    val starts = mutableListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
    return starts.toIntArray()
  }
}

/** Reads the file behind [document], under a read action. */
internal suspend fun fileOf(document: Document): VirtualFile? = readAction {
  com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
}
