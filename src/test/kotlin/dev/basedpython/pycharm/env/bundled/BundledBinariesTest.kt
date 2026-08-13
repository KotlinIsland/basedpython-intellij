package dev.basedpython.pycharm.env.bundled

import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure unit tests for [BundledBinaries]. The path and compatibility helpers take their environment
 * as parameters, and [BundledBinaries.find] takes the plugin root, so the whole lookup is drivable
 * against a temp directory with no plugin installation and no real binaries.
 */
class BundledBinariesTest {

    @TempDir
    lateinit var tmp: Path

    // --- Layout -------------------------------------------------------------

    @Test
    fun `bundled binaries live in the plugin's bin directory`() {
        val root = Path.of("/plugins/basedpython")
        assertEquals(Path.of("/plugins/basedpython/bin/by"), BundledBinaries.bundledPath(root, "by", windows = false))
        assertEquals(Path.of("/plugins/basedpython/bin/buff"), BundledBinaries.bundledPath(root, "buff", windows = false))
    }

    @Test
    fun `windows bundles carry the exe suffix`() {
        val root = Path.of("C:/plugins/basedpython")
        assertEquals("by.exe", BundledBinaries.bundledPath(root, "by", windows = true).fileName.toString())
    }

    @Test
    fun `the marker sits beside the binaries`() {
        val root = Path.of("/plugins/basedpython")
        assertEquals(Path.of("/plugins/basedpython/bin/platform.txt"), BundledBinaries.markerPath(root))
    }

    // --- Host compatibility -------------------------------------------------

    @Test
    fun `a distribution built for this host is usable`() {
        assertTrue(BundledBinaries.isUsableOnHost("mac-arm64", Platform.MAC_ARM64))
    }

    @Test
    fun `the marker tolerates the whitespace a written file carries`() {
        // The build writes the slug with a trailing newline.
        assertTrue(BundledBinaries.isUsableOnHost("linux-x64\n", Platform.LINUX_X64))
        assertTrue(BundledBinaries.isUsableOnHost(" MAC-X64 ", Platform.MAC_X64))
    }

    @Test
    fun `another platform's distribution is refused`() {
        // The zips are per-platform and nothing stops installing the wrong one. Refusing here is
        // what turns that into "no bundled binary" instead of "Bad CPU type in executable" from
        // inside an LSP start.
        assertFalse(BundledBinaries.isUsableOnHost("mac-arm64", Platform.LINUX_X64))
        assertFalse(BundledBinaries.isUsableOnHost("windows-x64", Platform.MAC_ARM64))
    }

    @Test
    fun `a marked distribution is refused on an unrecognised host`() {
        assertFalse(BundledBinaries.isUsableOnHost("linux-arm64", null))
    }

    @Test
    fun `an unmarked bin directory is trusted`() {
        // `runIde -PbundledBinariesDir=...` with hand-placed files, and any distribution assembled
        // outside the build: whoever put the binaries there knows what they are.
        for (host in listOf(Platform.MAC_ARM64, null)) {
            assertTrue(BundledBinaries.isUsableOnHost(null, host))
            assertTrue(BundledBinaries.isUsableOnHost("", host))
            assertTrue(BundledBinaries.isUsableOnHost("  \n", host))
        }
    }

    // --- find ---------------------------------------------------------------

    @Test
    fun `find returns nothing when the distribution bundles no binaries`() {
        assertNull(BundledBinaries.find("by", tmp, Platform.LINUX_X64, windows = false))
    }

    @Test
    fun `find returns nothing without a plugin directory`() {
        assertNull(BundledBinaries.find("by", null, Platform.LINUX_X64, windows = false))
    }

    @Test
    fun `find locates a matching bundled binary`() {
        writeBundle(slug = "linux-x64", "by", "buff")
        assertEquals(tmp.resolve("bin/by"), BundledBinaries.find("by", tmp, Platform.LINUX_X64, windows = false))
        assertEquals(tmp.resolve("bin/buff"), BundledBinaries.find("buff", tmp, Platform.LINUX_X64, windows = false))
    }

    @Test
    fun `find restores the execute bit the plugin installer drops`() {
        val exe = writeBundle(slug = "linux-x64", "by").single()
        exe.toFile().setExecutable(false)

        assertEquals(exe, BundledBinaries.find("by", tmp, Platform.LINUX_X64, windows = false))
        assertTrue(Files.isExecutable(exe), "find must repair the file rather than reject it")
    }

    @Test
    fun `find refuses another platform's bundle`() {
        writeBundle(slug = "mac-arm64", "by")
        assertNull(BundledBinaries.find("by", tmp, Platform.LINUX_X64, windows = false))
    }

    @Test
    fun `find only looks for the binary it was asked for`() {
        writeBundle(slug = "linux-x64", "by")
        assertNull(BundledBinaries.find("buff", tmp, Platform.LINUX_X64, windows = false))
    }

    /** Lays out a `bin/` directory the way the build does; returns the created binaries. */
    private fun writeBundle(slug: String?, vararg binaries: String): List<Path> {
        val bin = Files.createDirectories(tmp.resolve("bin"))
        if (slug != null) Files.writeString(bin.resolve("platform.txt"), "$slug\n")
        return binaries.map { name ->
            Files.writeString(bin.resolve(name), "#!/bin/sh\n").also { it.toFile().setExecutable(true) }
        }
    }
}
