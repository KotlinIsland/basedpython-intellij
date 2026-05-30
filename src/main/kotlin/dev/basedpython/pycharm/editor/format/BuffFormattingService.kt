package dev.basedpython.pycharm.editor.format

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Routes the IDE's **Reformat Code** (and reformat-selection) for `.by` files
 * through the `buff` CLI instead of the platform's generic formatter.
 *
 * The document text is written to a temporary `.by` file, `buff format` is run
 * against it (matching the invocation used by the format-on-save integration
 * and the "Format with buff" action), and the formatted result is read back and
 * handed to the platform via [AsyncFormattingRequest.onTextReady].
 *
 * `buff` does not support range formatting on stdin/text input, so selections
 * fall back to full-file formatting — the platform applies the result to the
 * requested range automatically.
 */
class BuffFormattingService : AsyncDocumentFormattingService() {

    override fun getName(): String = "buff"

    override fun getNotificationGroupId(): String = ByCli.NOTIFICATION_GROUP_ID

    override fun getFeatures(): Set<FormattingService.Feature> =
        setOf(FormattingService.Feature.AD_HOC_FORMATTING)

    override fun canFormat(file: PsiFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val context = request.context
        val project = context.project
        val vf = context.virtualFile
        return BuffFormattingTask(project, request, vf?.toNioPath(), vf)
    }

    private class BuffFormattingTask(
        private val project: Project,
        private val request: AsyncFormattingRequest,
        private val sourcePath: Path?,
        private val sourceFile: com.intellij.openapi.vfs.VirtualFile?,
    ) : FormattingTask {

        @Volatile
        private var cancelled = false

        override fun run() {
            if (cancelled) return

            // Write the in-memory document text to a temp file so `buff format`
            // (which operates on a path) sees exactly what is in the editor.
            val workDir = sourcePath?.parent
            val tempFile: Path = try {
                if (workDir != null) {
                    Files.createTempFile(workDir, ".buff-format-", ".by")
                } else {
                    Files.createTempFile("buff-format-", ".by")
                }
            } catch (e: Exception) {
                request.onError(BasedPythonBundle.message("notification.formatFailed.title"), e.message ?: BasedPythonBundle.message("format.tempFileFailed"))
                return
            }

            try {
                Files.write(tempFile, request.documentText.toByteArray(StandardCharsets.UTF_8))
                if (cancelled) return

                val out = ByCli.runBuff(
                    project,
                    "format", tempFile.toString(),
                    cwd = workDir ?: tempFile.parent,
                    contextFile = sourceFile,
                    title = "buff format",
                )
                if (out == null) {
                    request.onError(BasedPythonBundle.message("notification.formatFailed.title"), BasedPythonBundle.message("format.buffBinaryNotFound"))
                    return
                }
                if (cancelled) return
                if (out.exitCode != 0) {
                    request.onError(BasedPythonBundle.message("notification.formatFailed.title"), out.stderr.ifBlank { BasedPythonBundle.message("notification.exitCode", out.exitCode) })
                    return
                }

                val formatted = String(Files.readAllBytes(tempFile), StandardCharsets.UTF_8)
                request.onTextReady(formatted)
            } catch (e: Exception) {
                request.onError(BasedPythonBundle.message("notification.formatFailed.title"), e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { Files.deleteIfExists(tempFile) }
            }
        }

        override fun cancel(): Boolean {
            cancelled = true
            return true
        }

        override fun isRunUnderProgress(): Boolean = true
    }
}
