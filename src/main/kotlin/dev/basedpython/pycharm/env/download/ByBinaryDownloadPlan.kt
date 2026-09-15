package dev.basedpython.pycharm.env.download

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.basedpython.pycharm.env.manager.index.Pep440
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure, side-effect-free core for the "bundled fallback binary download" feature
 * (FEATURES.md §58). All functions are deterministic and take their environment
 * (os.name / os.arch / user home, the index's answer) as parameters so tests can drive every
 * platform without touching `System.getProperty`, the network or the filesystem.
 *
 * ### Where the binaries come from
 *
 * basedpython publishes `by` and `buff` in one place: the `basedpython` wheels on PyPI, one per
 * platform, with both executables under `basedpython-<version>.data/scripts/`. Its GitHub releases
 * carry only a `dist-manifest.json` — the release workflow is configured for wheels and no
 * standalone archives — so there is no per-binary asset URL to build. The index's JSON API names
 * every wheel with its SHA-256, which is what the download is checked against.
 *
 * Nothing in this object performs network or disk IO.
 */
object ByBinaryDownloadPlan {

    /** The distribution the binaries ship in. */
    const val DISTRIBUTION = "basedpython"

    /** PyPI's JSON API for [DISTRIBUTION]: every release, every file, every digest. */
    const val RELEASES_URL = "https://pypi.org/pypi/$DISTRIBUTION/json"

    /** Directory (relative to user home) under which downloaded binaries are installed. */
    const val INSTALL_DIR_NAME = ".basedpython"
    const val INSTALL_BIN_NAME = "bin"

    /** The two binaries this plugin can fetch. */
    val BINARY_NAMES: List<String> = listOf("by", "buff")

    /**
     * Supported per-OS/arch download targets. The [slug] names the platform to people and to the
     * bundled layout; [exe] is the executable suffix (`.exe` on Windows, else empty); [wheelTag] is
     * whether a wheel's platform tag is one this machine can run.
     */
    enum class Platform(val slug: String, val exe: String, val windows: Boolean, private val wheelTag: (String) -> Boolean) {
        MAC_ARM64("mac-arm64", "", false, { it.startsWith("macosx_") && it.endsWith("_arm64") }),
        MAC_X64("mac-x64", "", false, { it.startsWith("macosx_") && it.endsWith("_x86_64") }),
        // glibc builds. The musl wheels are published too, but an IDE's bundled JVM is a glibc
        // build, so a machine running this plugin on Linux runs glibc binaries.
        LINUX_X64("linux-x64", "", false, { it.startsWith("manylinux") && it.endsWith("_x86_64") }),
        LINUX_ARM64("linux-arm64", "", false, { it.startsWith("manylinux") && it.endsWith("_aarch64") }),
        WINDOWS_X64("windows-x64", ".exe", true, { it == "win_amd64" }),
        WINDOWS_ARM64("windows-arm64", ".exe", true, { it == "win_arm64" }),
        ;

        /**
         * True when the wheel called [filename] runs here.
         *
         * A wheel's platform tag is the last `-` field before `.whl`, and may be several tags
         * joined by `.` — `manylinux_2_17_x86_64.manylinux2014_x86_64` — any of which is enough.
         */
        fun runsWheel(filename: String): Boolean {
            if (!filename.endsWith(".whl")) return false
            val tags = filename.removeSuffix(".whl").substringAfterLast('-')
            return tags.split('.').any(wheelTag)
        }
    }

    /** One wheel the index offers: where to get it, and what its SHA-256 must be. */
    data class Wheel(val version: String, val filename: String, val url: String, val sha256: String)

    /**
     * The newest wheel in PyPI's [releasesJson] that runs on [platform], or null when there is none.
     *
     * Newest by PEP 440, pre-releases included — every basedpython release so far is one, and the
     * index's own "latest" (`info.version`) skips them and names a placeholder. A file that is
     * yanked, or that carries no SHA-256 to check it against, is never chosen.
     */
    fun newestWheel(releasesJson: String, platform: Platform): Wheel? {
        val releases = runCatching { JsonParser.parseString(releasesJson).asJsonObject.getAsJsonObject("releases") }
            .getOrNull() ?: return null
        return releases.keySet()
            .sortedWith(Pep440.NEWEST_FIRST)
            .firstNotNullOfOrNull { version ->
                releases.get(version)?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { it.takeIf { f -> f.isJsonObject }?.asJsonObject?.let { f -> wheel(version, f) } }
                    ?.firstOrNull { platform.runsWheel(it.filename) }
            }
    }

    private fun wheel(version: String, file: JsonObject): Wheel? {
        fun text(key: String): String? = file.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        if (file.get("yanked")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) return null
        val sha256 = file.getAsJsonObject("digests")?.get("sha256")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf { it.matches(SHA256) } ?: return null
        return Wheel(version, text("filename") ?: return null, text("url") ?: return null, sha256.lowercase())
    }

    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    /**
     * True when the wheel entry [entryName] is [binaryName] for [platform].
     *
     * Matched on the `.data/scripts/` tail, which is where a wheel keeps the executables it
     * installs onto `PATH`, so the versioned directory above it does not have to be predicted.
     */
    fun isBinaryEntry(entryName: String, binaryName: String, platform: Platform): Boolean =
        entryName.endsWith(".data/scripts/${executableFileName(binaryName, platform)}")

    /**
     * Detect the [Platform] from raw `os.name` / `os.arch` strings (as returned by
     * `System.getProperty`). Case-insensitive. Returns `null` for unrecognised
     * OS/arch combinations so callers can fall back gracefully.
     */
    fun detectPlatform(osName: String?, osArch: String?): Platform? {
        val name = osName?.lowercase()?.trim() ?: return null
        val arch = osArch?.lowercase()?.trim() ?: return null
        val isArm = arch.contains("aarch64") || arch.contains("arm64") || arch == "arm"
        val is64 = arch.contains("64") || arch == "amd64" || arch == "x86_64"
        return when {
            name.contains("mac") || name.contains("darwin") || name.contains("os x") ->
                if (isArm) Platform.MAC_ARM64 else Platform.MAC_X64
            // ARM before x64: an `aarch64` / `arm64` value satisfies [is64] as well, so testing the
            // width first would call every Windows-on-ARM machine x64.
            name.contains("win") ->
                if (isArm) Platform.WINDOWS_ARM64 else if (is64) Platform.WINDOWS_X64 else null
            name.contains("nux") || name.contains("nix") ->
                if (isArm) Platform.LINUX_ARM64 else if (is64) Platform.LINUX_X64 else null
            else -> null
        }
    }

    /** Local executable file name for [binaryName] on [platform] (adds `.exe` on Windows). */
    fun executableFileName(binaryName: String, platform: Platform): String =
        "$binaryName${platform.exe}"

    /** Plugin-managed install directory under [userHome]: `<home>/.basedpython/bin`. */
    fun installDir(userHome: String): Path =
        Paths.get(userHome, INSTALL_DIR_NAME, INSTALL_BIN_NAME)

    /**
     * Absolute install path for [binaryName] on [platform] under [userHome]:
     * `<home>/.basedpython/bin/<name><ext>`.
     */
    fun installPath(userHome: String, binaryName: String, platform: Platform): Path =
        installDir(userHome).resolve(executableFileName(binaryName, platform))
}
