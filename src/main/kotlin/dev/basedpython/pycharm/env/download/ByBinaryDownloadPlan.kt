package dev.basedpython.pycharm.env.download

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Pure, side-effect-free core for the "bundled fallback binary download" feature
 * (FEATURES.md §58). All functions are deterministic and take their environment
 * (os.name / os.arch / user home) as parameters so tests can drive every platform
 * without touching `System.getProperty` or the filesystem.
 *
 * Nothing in this object performs network or disk IO.
 */
object ByBinaryDownloadPlan {

    /** Base URL for the GitHub release assets. `{...}` placeholders filled by [downloadUrl]. */
    const val BASE_URL = "https://github.com/basedpython/basedpython/releases/download"

    /** Default release version used when a caller does not supply one. */
    const val DEFAULT_VERSION = "1.0.0"

    /** Directory (relative to user home) under which downloaded binaries are installed. */
    const val INSTALL_DIR_NAME = ".basedpython"
    const val INSTALL_BIN_NAME = "bin"

    /** The two binaries this plugin can fetch. */
    val BINARY_NAMES: List<String> = listOf("by", "buff")

    /**
     * Supported per-OS/arch download targets. The [slug] is the platform fragment used
     * in the asset name; [exe] is the executable suffix (`.exe` on Windows, else empty).
     */
    enum class Platform(val slug: String, val exe: String, val windows: Boolean) {
        MAC_ARM64("mac-arm64", "", false),
        MAC_X64("mac-x64", "", false),
        LINUX_X64("linux-x64", "", false),
        LINUX_ARM64("linux-arm64", "", false),
        WINDOWS_X64("windows-x64", ".exe", true),
        WINDOWS_ARM64("windows-arm64", ".exe", true),
    }

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

    /** Normalise a possibly-blank/`v`-prefixed version into a bare `x.y.z` string. */
    fun normalizeVersion(version: String?): String {
        val v = version?.trim().orEmpty()
        if (v.isEmpty()) return DEFAULT_VERSION
        return v.removePrefix("v").removePrefix("V").ifEmpty { DEFAULT_VERSION }
    }

    /** Asset file name for [binaryName] on [platform], e.g. `by-mac-arm64` or `buff-windows-x64.exe`. */
    fun assetName(binaryName: String, platform: Platform): String =
        "$binaryName-${platform.slug}${platform.exe}"

    /**
     * Full download URL for [binaryName] at [version] on [platform], rooted at [baseUrl].
     * Example: `.../download/v1.0.0/by-mac-arm64`.
     */
    fun downloadUrl(
        binaryName: String,
        version: String?,
        platform: Platform,
        baseUrl: String = BASE_URL,
    ): String {
        val ver = normalizeVersion(version)
        val root = baseUrl.trimEnd('/')
        return "$root/v$ver/${assetName(binaryName, platform)}"
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
