package dev.basedpython.pycharm.editor.format

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
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
 * `buff` does not range-format, so reformat-selection falls back to the whole file; the platform
 * applies the result to the requested range itself.
 */
class BuffFormattingService : AsyncDocumentFormattingService() {

    override fun getName(): String = "buff"

    override fun getNotificationGroupId(): String = ByCli.NOTIFICATION_GROUP_ID

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.AD_HOC_FORMATTING)

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

            val edits = ByCleanup.requestEdits(server, file, ByCleanupOp.FormatAndOrganizeImports)
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
            request.onTextReady(ByCleanup.applyEditsTo(request.documentText, edits))
        }

        override fun cancel(): Boolean {
            cancelled = true
            return true
        }

        override fun isRunUnderProgress(): Boolean = true
    }
}
