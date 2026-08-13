package dev.basedpython.pycharm.env.download

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.HttpRequests
import dev.basedpython.pycharm.env.Executables
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.ui.log.BasedPythonLogNotifications
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path

/**
 * FEATURES.md §58 — when the `by` / `buff` binaries cannot be resolved, offer to download a
 * per-OS prebuilt binary into a plugin-managed location (`~/.basedpython/bin`) and point
 * [BasedPythonSettings] at it.
 *
 * The pure planning logic lives in [ByBinaryDownloadPlan]; this class only wires platform
 * detection, user confirmation, off-EDT download + IO, and notifications together.
 */
class DownloadBinariesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && missingBinaries(project).isNotEmpty()
    }

    /** Names of the binaries that currently fail to resolve for [project]. */
    private fun missingBinaries(project: Project): List<String> {
        val missing = mutableListOf<String>()
        if (!BasedPythonBinaries.isByAvailable(project)) missing.add("by")
        if (!BasedPythonBinaries.isBuffAvailable(project)) missing.add("buff")
        return missing
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val platform = ByBinaryDownloadPlan.detectPlatform(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
        )
        if (platform == null) {
            BasedPythonLogNotifications.create(
                project,
                TITLE,
                BasedPythonBundle.message("download.unsupportedPlatform"),
                NotificationType.WARNING,
            ).notify(project)
            return
        }

        val missing = missingBinaries(project).ifEmpty { ByBinaryDownloadPlan.BINARY_NAMES }
        val version = BasedPythonSettings.getInstance(project).effectivePythonVersion
        val home = System.getProperty("user.home")

        val choice = Messages.showYesNoDialog(
            project,
            BasedPythonBundle.message(
                "download.confirm.message",
                missing.joinToString(" and "),
                platform.slug,
                ByBinaryDownloadPlan.installDir(home),
            ),
            TITLE,
            Messages.getQuestionIcon(),
        )
        if (choice != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("download.progress.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                val installed = mutableListOf<String>()
                val failures = mutableListOf<String>()
                for ((idx, name) in missing.withIndex()) {
                    indicator.checkCanceled()
                    indicator.fraction = idx.toDouble() / missing.size
                    indicator.text = BasedPythonBundle.message("download.progress.item", name)
                    try {
                        val url = ByBinaryDownloadPlan.downloadUrl(name, version, platform)
                        val target = ByBinaryDownloadPlan.installPath(home, name, platform)
                        downloadTo(url, target)
                        markExecutable(target, platform)
                        applyToSettings(project, name, target)
                        installed.add(name)
                    } catch (ex: Exception) {
                        LOG.warn("Failed to download $name", ex)
                        failures.add("$name (${ex.message})")
                    }
                }
                notifyResult(project, installed, failures)
            }
        })
    }

    /** Streams [url] into [target], creating parent dirs. Runs off the EDT (within the task). */
    private fun downloadTo(url: String, target: Path) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".part")
        HttpRequests.request(url).productNameAsUserAgent().saveToFile(tmp.toFile(), null)
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Adds the execute bits on POSIX filesystems; no-op elsewhere. Best effort — see [Executables]. */
    private fun markExecutable(target: Path, platform: ByBinaryDownloadPlan.Platform) {
        if (platform.windows) return
        Executables.makeExecutable(target)
    }

    private fun applyToSettings(project: Project, name: String, target: Path) {
        val settings = BasedPythonSettings.getInstance(project)
        when (name) {
            "by" -> settings.byPath = target.toString()
            "buff" -> settings.buffPath = target.toString()
        }
    }

    private fun notifyResult(project: Project, installed: List<String>, failures: List<String>) {
        if (failures.isEmpty()) {
            BasedPythonLogNotifications.create(
                project,
                TITLE,
                BasedPythonBundle.message("download.result.success", installed.joinToString(" and ")),
                NotificationType.INFORMATION,
            ).notify(project)
        } else {
            val ok = if (installed.isEmpty()) "" else BasedPythonBundle.message("download.result.partialPrefix", installed.joinToString(", "))
            BasedPythonLogNotifications.create(
                project,
                TITLE,
                BasedPythonBundle.message("download.result.failed", ok, failures.joinToString("; ")),
                NotificationType.ERROR,
            ).notify(project)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DownloadBinariesAction::class.java)
        private val TITLE get() = BasedPythonBundle.message("download.title")
    }
}
