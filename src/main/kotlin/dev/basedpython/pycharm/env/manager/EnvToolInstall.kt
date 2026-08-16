package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import dev.basedpython.pycharm.env.Executables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installing a backend's own tool, so a machine without uv is one click rather than one web search
 * away from a working project.
 *
 * ### Why a download rather than the documented installer
 *
 * uv's install instructions are `curl -LsSf https://astral.sh/uv/install.sh | sh`. This plugin is
 * not going to pipe a downloaded script into a shell on a button press. The release archive holds a
 * static binary with no dependencies and no install steps — the script's entire job is to pick the
 * right archive and unpack it, which is what this does, without handing an anonymous script the
 * user's shell.
 *
 * The binary lands in `~/.basedpython/bin`, the same plugin-managed directory the `by` / `buff`
 * download uses. Nothing outside that directory is touched: no shell profile is edited, no `PATH` is
 * changed, and nothing else on the machine can be shadowed by it — [EnvTools] looks there directly.
 */
internal object EnvToolInstall {

    private val LOG = Logger.getInstance(EnvToolInstall::class.java)

    /** What an install attempt produced. */
    sealed interface Outcome {
        data class Installed(val path: Path) : Outcome

        /** The backend cannot install itself, or publishes nothing for this OS/arch. */
        data object Unsupported : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * Downloads and unpacks [backend]'s tool. Blocking; call from a background task.
     *
     * [indicator] is only used for cancellation and progress text — the download itself reports no
     * fraction, because these archives are a few tens of megabytes over a redirect chain that does
     * not always carry a length.
     *
     * [target] defaults to the plugin-managed location and is a parameter so a test can install into
     * a temp directory. Nothing in production passes it.
     */
    fun install(
        backend: EnvBackend,
        indicator: ProgressIndicator?,
        target: Path? = EnvTools.managedPath(backend),
    ): Outcome {
        val plan = backend.installer?.plan(System.getProperty("os.name"), System.getProperty("os.arch"))
            ?: return Outcome.Unsupported
        if (target == null) return Outcome.Failed("no user home")

        return try {
            indicator?.text = "Downloading ${backend.executableName}…"
            Files.createDirectories(target.parent)
            // A directory of its own so a stray entry the filter lets through cannot land next to
            // the installed binaries, and so cleanup is one recursive delete.
            val work = Files.createTempDirectory(target.parent, ".${backend.executableName}-install")
            try {
                val archive = work.resolve(plan.url.substringAfterLast('/'))
                HttpRequests.request(plan.url).productNameAsUserAgent().saveToFile(archive.toFile(), indicator)
                indicator?.checkCanceled()

                indicator?.text = "Unpacking ${backend.executableName}…"
                val extracted = extract(archive, work, plan)
                    ?: return Outcome.Failed("${plan.memberSuffix} not found in ${plan.url.substringAfterLast('/')}")

                Files.move(extracted, target, StandardCopyOption.REPLACE_EXISTING)
                // Archive modes survive a tar but not a zip, and the platform's extractor does not
                // promise to restore them either — so the bit is set explicitly rather than assumed.
                Executables.makeExecutable(target)
                if (!Files.isExecutable(target)) {
                    return Outcome.Failed("$target is not executable")
                }
                Outcome.Installed(target)
            } finally {
                deleteRecursively(work)
            }
        } catch (e: Exception) {
            LOG.warn("Failed to install ${backend.executableName}", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Unpacks the one member [plan] names, and returns where it landed.
     *
     * The filter matters beyond tidiness: it is what keeps an archive from writing anything but the
     * single executable this asked for. Entries are matched on the tail of their path so a versioned
     * top-level directory inside the tarball (`uv-aarch64-apple-darwin/uv`) does not have to be
     * predicted, while a name that merely *contains* the executable's name (`uv-completions`) still
     * misses.
     */
    private fun extract(archive: Path, into: Path, plan: EnvToolDownload): Path? {
        val outDir = into.resolve("unpacked")
        val wanted = plan.memberSuffix
        val decompressor = when (plan.archive) {
            EnvToolDownload.ArchiveKind.TAR_GZ -> Decompressor.Tar(archive)
            EnvToolDownload.ArchiveKind.ZIP -> Decompressor.Zip(archive)
        }
        decompressor
            .filter { entryName -> entryName == wanted || entryName.endsWith("/$wanted") }
            .extract(outDir)
        return Files.walk(outDir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == wanted }.findFirst()
                .orElse(null)
        }
    }

    /** Best-effort cleanup of the scratch directory; a leftover must not fail an install that worked. */
    private fun deleteRecursively(dir: Path) {
        try {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
            }
        } catch (e: Exception) {
            LOG.debug("Could not clean up $dir", e)
        }
    }
}
