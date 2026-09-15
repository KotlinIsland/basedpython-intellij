package dev.basedpython.pycharm.env.download

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import dev.basedpython.pycharm.env.Executables
import dev.basedpython.pycharm.env.manager.EnvOperations
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.ui.log.BasedPythonLogNotifications
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException

/**
 * FEATURES.md §58 — when the `by` / `buff` binaries cannot be resolved, offer to download a
 * per-OS prebuilt binary into a plugin-managed location (`~/.basedpython/bin`) and point
 * [BasedPythonSettings] at it.
 *
 * The binaries come from the newest `basedpython` wheel on PyPI for this platform — the only place
 * basedpython publishes them, see [ByBinaryDownloadPlan]. The wheel is checked against the SHA-256
 * the index lists for it before anything is taken out of it, and the whole download is cancellable.
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
                val installed = try {
                    install(indicator, platform, home, missing)
                } catch (ex: Exception) {
                    // Cancelling is the user pressing stop, not a download that failed.
                    if (ex is ControlFlowException || ex is CancellationException) throw ex
                    LOG.warn("Failed to download basedpython binaries", ex)
                    notifyResult(project, emptyList(), listOf(ex.message ?: ex.javaClass.simpleName))
                    return
                }
                installed.forEach { (name, target) -> applyToSettings(project, name, target) }
                notifyResult(project, installed.map { it.first }, emptyList())
                // The language servers were started from — or failed to find — a binary path that is
                // no longer the answer, and the "not found" banner is a cached verdict.
                EnvOperations.afterEnvironmentChanged(project)
            }
        })
    }

    /**
     * Downloads the wheel, verifies it, and installs [names] out of it; returns what was installed
     * where. Throws on anything short of every binary installed, so settings are never pointed at
     * half a download.
     */
    private fun install(
        indicator: ProgressIndicator,
        platform: ByBinaryDownloadPlan.Platform,
        home: String,
        names: List<String>,
    ): List<Pair<String, Path>> {
        indicator.isIndeterminate = true
        indicator.text = BasedPythonBundle.message("download.progress.index")
        val releases = HttpRequests.request(ByBinaryDownloadPlan.RELEASES_URL)
            .productNameAsUserAgent()
            .readString(indicator)
        val wheel = ByBinaryDownloadPlan.newestWheel(releases, platform)
            ?: error(BasedPythonBundle.message("download.noWheel", ByBinaryDownloadPlan.DISTRIBUTION, platform.slug))

        val installDir = ByBinaryDownloadPlan.installDir(home)
        Files.createDirectories(installDir)
        val work = Files.createTempDirectory(installDir, ".basedpython-download")
        try {
            indicator.text = BasedPythonBundle.message("download.progress.item", wheel.filename)
            val archive = work.resolve(wheel.filename)
            HttpRequests.request(wheel.url).productNameAsUserAgent().saveToFile(archive.toFile(), indicator)
            indicator.checkCanceled()

            val actual = Checksums.sha256(archive)
            check(actual == wheel.sha256) {
                BasedPythonBundle.message("download.checksumMismatch", wheel.filename, wheel.sha256, actual)
            }

            val unpacked = work.resolve("unpacked")
            Decompressor.Zip(archive)
                .filter { entry -> names.any { ByBinaryDownloadPlan.isBinaryEntry(entry, it, platform) } }
                .extract(unpacked)
            indicator.checkCanceled()

            val found = names.map { name ->
                val file = Files.walk(unpacked).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .filter { ByBinaryDownloadPlan.isBinaryEntry(unpacked.relativize(it).joinToString("/"), name, platform) }
                        .findFirst()
                        .orElse(null)
                } ?: error(BasedPythonBundle.message("download.binaryMissing", name, wheel.filename))
                name to file
            }
            return found.map { (name, file) ->
                val target = ByBinaryDownloadPlan.installPath(home, name, platform)
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
                if (!platform.windows) Executables.makeExecutable(target)
                name to target
            }
        } finally {
            deleteRecursively(work)
        }
    }

    /** Best-effort cleanup of the scratch directory; a leftover must not fail an install that worked. */
    private fun deleteRecursively(dir: Path) {
        runCatching {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
        }
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
