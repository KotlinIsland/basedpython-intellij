package dev.basedpython.pycharm.editor.format

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.awaitBy
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.format.ByCleanupOp
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * *Reformat Code* for the files this plugin owns.
 *
 * Reformatting here means laying the file out **and** putting its imports in order, which is
 * [ByCleanupOp.FormatAndOrganizeImports] — one request answered against one buffer, so there is one
 * diff and one undo step. It stops short of dropping the imports nothing uses: that is what
 * *Optimize Imports* is for, and deleting code is not something laying a file out should decide.
 *
 * The work is asked of the running `buff` server rather than of a `buff format` subprocess. A
 * subprocess rediscovers the project's configuration on every call and resolves it without the
 * settings the editor handed the server at startup, so the two can disagree about which rules
 * apply — and it could only ever format, since sorting imports is not something `buff format` does.
 *
 * Reformatting a selection, or the ranges a refactoring hands over, lays out only those:
 * `textDocument/rangeFormatting` for each range, which `buff` answers by laying out again the
 * statements the range covers — as PyCharm reformats only the selected part of a `.py`. Imports are
 * sorted only by a whole-file reformat; sorting them is not something a selection asked for.
 */
class BuffFormattingService : AsyncDocumentFormattingService() {

    override fun getName(): String = "buff"

    override fun getNotificationGroupId(): String = ByCli.NOTIFICATION_GROUP_ID

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.AD_HOC_FORMATTING, FormattingService.Feature.FORMAT_FRAGMENTS)

    override fun canFormat(file: PsiFile): Boolean =
        file.virtualFile?.fileType == BasedPythonFileType.INSTANCE &&
            BasedPythonSettings.getInstance(file.project).buffFormatting

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val file = context.virtualFile ?: return null
        return BuffFormattingTask(context.project, request, file)
    }

    private class BuffFormattingTask(
        private val project: Project,
        private val request: AsyncFormattingRequest,
        private val file: VirtualFile,
    ) : FormattingTask {

        @Volatile
        private var cancelled = false

        override fun run() {
            if (cancelled) return

            val server = ByCleanup.findServer(project, file) ?: run {
                request.onError(
                    BasedPythonBundle.message("notification.formatFailed.title"),
                    BasedPythonBundle.message("format.serverNotRunning"),
                )
                return
            }

            if (!ByCleanup.advertises(server, ByCleanupOp.FormatAndOrganizeImports)) {
                request.onError(
                    BasedPythonBundle.message("notification.formatFailed.title"),
                    BasedPythonBundle.message("format.serverTooOld", ByCleanupOp.FormatAndOrganizeImports.kind),
                )
                return
            }

            // The server only ever holds a document the editor opened, so a file with no document
            // is not one it can answer for.
            val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file) }
                ?: run {
                    request.onError(
                        BasedPythonBundle.message("notification.formatFailed.title"),
                        BasedPythonBundle.message("format.serverDidNotAnswer"),
                    )
                    return
                }
            val text = request.documentText
            val ranges = request.formattingRanges
            val whole = ranges.isEmpty() || ranges.any { it.startOffset <= 0 && it.endOffset >= text.length }
            val edits = runBlockingCancellable {
                if (whole) {
                    ByCleanup.requestEdits(server, file, document, ByCleanupOp.FormatAndOrganizeImports)?.edits
                } else {
                    BuffRangeFormatting.edits(server, file, text, ranges, readAction { BuffRangeFormatting.options(project) })
                }
            }
            if (cancelled) return
            if (edits == null) {
                request.onError(
                    BasedPythonBundle.message("notification.formatFailed.title"),
                    BasedPythonBundle.message("format.serverDidNotAnswer"),
                )
                return
            }

            // An empty list means the file was already laid out the way `buff` wants it. Handing
            // back the unchanged text is how this service says "nothing to do"; onError would
            // report a failure that did not happen.
            request.onTextReady(ByCleanup.applyEditsTo(text, edits))
        }

        override fun cancel(): Boolean {
            cancelled = true
            return true
        }

        override fun isRunUnderProgress(): Boolean = true
    }
}

/** Reformat Selection's request: `textDocument/rangeFormatting` for each range. */
internal object BuffRangeFormatting {

    /** The indent the editor uses for `.by` files, which LSP sends with a formatting request. */
    fun options(project: Project): FormattingOptions {
        val indent = CodeStyle.getSettings(project).getIndentOptions(BasedPythonFileType.INSTANCE)
        return FormattingOptions(indent.INDENT_SIZE, !indent.USE_TAB_CHARACTER)
    }

    /**
     * `buff`'s edits laying out each of [ranges] of [text], or null when it did not answer for one
     * of them. The ranges are disjoint and asked about the same text, so their edits are too.
     */
    suspend fun edits(server: LspClient, file: VirtualFile, text: String, ranges: List<TextRange>, options: FormattingOptions): List<TextEdit>? {
        val identifier = server.getDocumentIdentifier(file)
        return ranges.flatMap { range ->
            val params = DocumentRangeFormattingParams(identifier, options, lsp4jRange(text, range))
            when (val answer = server.awaitBy("textDocument/rangeFormatting") { it.textDocumentService.rangeFormatting(params) }) {
                is ByAnswer.Answer -> answer.value
                ByAnswer.None -> emptyList()
                ByAnswer.Failed -> return null
            }
        }
    }

    /** [range] of [text] as LSP's lines and UTF-16 columns, as the server reads the same text. */
    fun lsp4jRange(text: String, range: TextRange): Range = Range(position(text, range.startOffset), position(text, range.endOffset))

    private fun position(text: String, offset: Int): Position {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return Position(StringUtil.countNewLines(text.subSequence(0, lineStart)), offset - lineStart)
    }
}
