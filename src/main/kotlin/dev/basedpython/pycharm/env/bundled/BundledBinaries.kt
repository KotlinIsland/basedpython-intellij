package dev.basedpython.pycharm.env.bundled

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import dev.basedpython.pycharm.env.Executables
import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan
import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan.Platform
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `by` / `buff` shipped inside the plugin itself, at `<plugin>/bin/`.
 *
 * The counterpart to [dev.basedpython.pycharm.env.download.DownloadBinariesAction]: same binaries,
 * same per-OS asset naming ([ByBinaryDownloadPlan.Platform]), but placed at *build* time by
 * `-PbundledBinariesDir` (see build.gradle.kts and .github/workflows/bundled-distributions.yml)
 * rather than fetched at runtime. A distribution built that way needs no network and no install
 * step for the toolchain to work.
 *
 * **One platform per distribution.** The binaries are ~200 MB each, so a zip carrying all five
 * targets is not a thing anyone can ship or download; the build produces one zip per platform
 * instead. That is also why [PLATFORM_MARKER] exists — nothing stops a user from installing the
 * mac-arm64 zip on a linux box, and a bundled binary that cannot possibly exec should be skipped
 * during resolution rather than surfaced as "Bad CPU type in executable" from deep inside an LSP
 * start. An *unmarked* `bin/` directory is trusted: that is the hand-assembled sandbox case
 * (`./gradlew runIde -PbundledBinariesDir=...`), where whoever put the files there knows.
 *
 * The path helpers are pure and take their environment as parameters, matching [ByBinaryDownloadPlan].
 */
object BundledBinaries {

    private val LOG = Logger.getInstance(BundledBinaries::class.java)

    /** Must match `<id>` in plugin.xml — the plugin looking itself up to find its own directory. */
    const val PLUGIN_ID: String = "dev.basedpython.ide"

    /** Directory inside the plugin distribution holding the binaries. */
    const val BIN_DIR: String = "bin"

    /** File in [BIN_DIR] naming the [Platform.slug] the distribution was built for. */
    const val PLATFORM_MARKER: String = "platform.txt"

    // --- Pure path/compatibility helpers (unit tested) ----------------------

    /** Where a bundled [binary] lives under [pluginRoot]. */
    fun bundledPath(pluginRoot: Path, binary: String, windows: Boolean): Path =
        pluginRoot.resolve(BIN_DIR).resolve(if (windows) "$binary.exe" else binary)

    /** The [PLATFORM_MARKER] path under [pluginRoot]. */
    fun markerPath(pluginRoot: Path): Path = pluginRoot.resolve(BIN_DIR).resolve(PLATFORM_MARKER)

    /**
     * Whether binaries marked as [bundledSlug] can run on a [host] platform.
     *
     * A blank or absent marker means "unmarked" and is accepted — see the class doc. An
     * unrecognised host ([host] `null`) with a marked distribution is rejected: the marker says
     * these binaries are for something specific, and we cannot confirm this machine is it.
     */
    fun isUsableOnHost(bundledSlug: String?, host: Platform?): Boolean {
        val slug = bundledSlug?.trim()?.lowercase().orEmpty()
        if (slug.isEmpty()) return true
        return slug == host?.slug
    }

    // --- Runtime lookup -----------------------------------------------------

    /**
     * The installed plugin's own directory, or `null` when it cannot be worked out (which is the
     * case in plain unit tests — there is no plugin installation to find).
     *
     * Derived from where this class was loaded from rather than asked of the platform: every
     * plugin-descriptor lookup there is `@ApiStatus.Internal` — `PluginManagerCore.getPlugin`,
     * `PluginManager.getPlugin`, `getPluginByClass`, `findEnabledPlugin`, and the
     * `PluginAwareClassLoader` a plugin class is loaded by — and the one public method,
     * `getPluginByClassName`, returns a bare `PluginId` with no path on it. See
     * docs/internal-api.md.
     *
     * Read from this class's own resource URL, not its `ProtectionDomain`: the platform's
     * `UrlClassLoader` defines every plugin class with a null protection domain, so the code source
     * has no location and a lookup through it finds nothing in any real IDE. Measured on 263.5993
     * with the installed 0.0.2 jar loaded through that classloader: code source `(null)`, resource
     * `jar:file:…/plugins/basedpython-intellij/lib/basedpython-intellij-0.0.2-mac-arm64.jar!/…`.
     */
    fun pluginRoot(): Path? =
        try {
            pluginRoot(BundledBinaries::class.java.getResource("${BundledBinaries::class.java.simpleName}.class"))
        } catch (ex: Exception) {
            LOG.debug("Could not locate the plugin directory", ex)
            null
        }

    /**
     * The plugin directory holding the jar that [classResource] — the URL a classloader gives for
     * one of the plugin's own `.class` files — was loaded from.
     *
     * An installed plugin is laid out as `<plugin>/lib/<jar>`, so the root is the jar's
     * grandparent. Anything else — a class loaded from a directory, as in a test, or an unexpected
     * layout — returns null, the same answer as no installation. A wrong directory would be worse
     * than none: it is where bundled `by` and `buff` binaries are looked for.
     */
    fun pluginRoot(classResource: URL?): Path? {
        if (classResource?.protocol != "jar") return null
        // `jar:<url of the jar>!/<entry>`; the jar's URL is percent-encoded, which URI decodes.
        val jarUrl = classResource.path.substringBefore("!/", missingDelimiterValue = "")
        val jar = try {
            URI(jarUrl).takeIf { it.scheme == "file" }?.let(Paths::get)
        } catch (_: URISyntaxException) {
            null
        } ?: return null
        return jar.takeIf { it.fileName.toString().endsWith(".jar") }
            ?.parent?.takeIf { it.fileName.toString() == "lib" }
            ?.parent
    }

    /**
     * The bundled [binary] for this machine, or `null` when this distribution ships none, ships
     * another platform's, or the file cannot be made executable.
     *
     * The plugin zip loses unix modes on install, so a bundled binary is normally present but not
     * executable on first use; [Executables.makeExecutable] repairs that in place, once — the
     * repair only runs while the bit is actually missing.
     */
    fun find(
        binary: String,
        pluginRoot: Path? = pluginRoot(),
        host: Platform? = ByBinaryDownloadPlan.detectPlatform(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
        ),
        windows: Boolean = SystemInfo.isWindows,
    ): Path? {
        val root = pluginRoot ?: return null
        val exe = bundledPath(root, binary, windows)
        if (!Files.isRegularFile(exe)) return null

        val marker = readMarker(root)
        if (!isUsableOnHost(marker, host)) {
            LOG.info("Ignoring bundled $binary: distribution is for $marker, host is ${host?.slug ?: "unknown"}")
            return null
        }
        if (!Files.isExecutable(exe) && !Executables.makeExecutable(exe)) {
            LOG.warn("Bundled $binary at $exe is not executable and could not be made executable")
            return null
        }
        return exe
    }

    /** [PLATFORM_MARKER]'s contents, or `null` when it is absent or unreadable. */
    private fun readMarker(pluginRoot: Path): String? =
        try {
            markerPath(pluginRoot).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        } catch (ex: Exception) {
            LOG.debug("Could not read the bundled platform marker", ex)
            null
        }
}
