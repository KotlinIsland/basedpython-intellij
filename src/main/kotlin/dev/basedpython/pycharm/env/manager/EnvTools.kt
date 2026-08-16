package dev.basedpython.pycharm.env.manager

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a backend's own tool lives on this machine.
 *
 * Three places, in order: the plugin's install directory, `PATH`, and the tool's conventional home.
 *
 * The plugin's directory goes first because a user who pressed *Install uv* is entitled to get the
 * uv they installed, not one that a shell profile happens to put on `PATH` — and because that is the
 * copy this plugin can reason about.
 *
 * The conventional locations are the part that makes this work where it is most often reported not
 * to. An IDE started from a desktop launcher inherits the session's `PATH`, not a login shell's, so
 * `~/.local/bin` — where uv's own installer puts it, and where nearly every uv on macOS and Linux
 * actually is — is frequently absent from what the IDE can see. Checking the two directories the
 * installers use costs two `stat` calls and removes the single most common "uv is installed, the
 * IDE cannot find it" report.
 */
object EnvTools {

    /** Locate the tool [backend] drives, or null when it is not installed anywhere we look. */
    fun find(backend: EnvBackend): Path? {
        val name = executableName(backend.executableName)
        managedDir()?.resolve(name)?.takeIf { Files.isExecutable(it) }?.let { return it }
        PathEnvironmentVariableUtil.findInPath(name)?.toPath()?.let { return it }
        return conventionalDirs().asSequence()
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) }
    }

    /** True when the tool [backend] needs is present. */
    fun isInstalled(backend: EnvBackend): Boolean = find(backend) != null

    /**
     * Where the plugin installs tools it downloads: the same `~/.basedpython/bin` the `by` / `buff`
     * download action uses, so there is one plugin-managed directory rather than one per feature.
     */
    fun managedDir(): Path? =
        System.getProperty("user.home")?.let { ByBinaryDownloadPlan.installDir(it) }

    /** The install path for [backend]'s tool inside [managedDir]. */
    fun managedPath(backend: EnvBackend): Path? =
        managedDir()?.resolve(executableName(backend.executableName))

    /** `PATH` lookups match the exact file name and do not apply PATHEXT, so add it ourselves. */
    fun executableName(base: String): String = if (SystemInfo.isWindows) "$base.exe" else base

    /**
     * Directories these tools install themselves into, which an IDE's `PATH` often does not include.
     *
     * `~/.local/bin` is where uv's install script and `pipx` put things; `~/.cargo/bin` is where a
     * `cargo install` lands. Both are user-writable directories the user chose to install into, so
     * reading an executable out of them is no more trusting than reading one off `PATH`.
     */
    private fun conventionalDirs(): List<Path> {
        val home = System.getProperty("user.home") ?: return emptyList()
        return listOf(
            Paths.get(home, ".local", "bin"),
            Paths.get(home, ".cargo", "bin"),
        )
    }
}
