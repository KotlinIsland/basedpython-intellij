package dev.basedpython.pycharm.env.download

import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure unit tests for [ByBinaryDownloadPlan]. No application service or
 * filesystem access is required — every function takes its environment as parameters.
 */
class ByBinaryDownloadPlanTest {

    // --- detectPlatform: macOS ---------------------------------------------

    @Test
    fun `mac arm64 from Mac OS X aarch64`() {
        assertEquals(Platform.MAC_ARM64, ByBinaryDownloadPlan.detectPlatform("Mac OS X", "aarch64"))
    }

    @Test
    fun `mac arm64 from darwin arm64`() {
        assertEquals(Platform.MAC_ARM64, ByBinaryDownloadPlan.detectPlatform("darwin", "arm64"))
    }

    @Test
    fun `mac x64 from Mac OS X x86_64`() {
        assertEquals(Platform.MAC_X64, ByBinaryDownloadPlan.detectPlatform("Mac OS X", "x86_64"))
    }

    @Test
    fun `mac x64 from Mac OS X amd64`() {
        assertEquals(Platform.MAC_X64, ByBinaryDownloadPlan.detectPlatform("Mac OS X", "amd64"))
    }

    // --- detectPlatform: Windows -------------------------------------------

    @Test
    fun `windows x64 from Windows 11 amd64`() {
        assertEquals(Platform.WINDOWS_X64, ByBinaryDownloadPlan.detectPlatform("Windows 11", "amd64"))
    }

    @Test
    fun `windows x64 from Windows 10 x86_64`() {
        assertEquals(Platform.WINDOWS_X64, ByBinaryDownloadPlan.detectPlatform("Windows 10", "x86_64"))
    }

    @Test
    fun `windows 32-bit x86 is unsupported`() {
        assertNull(ByBinaryDownloadPlan.detectPlatform("Windows 7", "x86"))
    }

    // --- detectPlatform: Linux ---------------------------------------------

    @Test
    fun `linux x64 from Linux amd64`() {
        assertEquals(Platform.LINUX_X64, ByBinaryDownloadPlan.detectPlatform("Linux", "amd64"))
    }

    @Test
    fun `linux x64 from Linux x86_64`() {
        assertEquals(Platform.LINUX_X64, ByBinaryDownloadPlan.detectPlatform("Linux", "x86_64"))
    }

    @Test
    fun `linux arm64 from Linux aarch64`() {
        assertEquals(Platform.LINUX_ARM64, ByBinaryDownloadPlan.detectPlatform("Linux", "aarch64"))
    }

    @Test
    fun `linux arm64 from Linux arm64`() {
        assertEquals(Platform.LINUX_ARM64, ByBinaryDownloadPlan.detectPlatform("Linux", "arm64"))
    }

    @Test
    fun `linux 32-bit arm is unsupported`() {
        // "arm" (non-64) on linux is not one of our published targets.
        assertNull(ByBinaryDownloadPlan.detectPlatform("Linux", "i686"))
    }

    // --- detectPlatform: case-insensitivity & whitespace -------------------

    @Test
    fun `detection is case-insensitive and trims`() {
        assertEquals(Platform.MAC_ARM64, ByBinaryDownloadPlan.detectPlatform("  MAC OS X  ", "  ARM64 "))
        assertEquals(Platform.WINDOWS_X64, ByBinaryDownloadPlan.detectPlatform("WINDOWS", "AMD64"))
        assertEquals(Platform.LINUX_X64, ByBinaryDownloadPlan.detectPlatform("LINUX", "X86_64"))
    }

    // --- detectPlatform: unknown / null fallbacks --------------------------

    @Test
    fun `unknown os returns null`() {
        assertNull(ByBinaryDownloadPlan.detectPlatform("Solaris", "sparc"))
    }

    @Test
    fun `null os returns null`() {
        assertNull(ByBinaryDownloadPlan.detectPlatform(null, "amd64"))
    }

    @Test
    fun `null arch returns null`() {
        assertNull(ByBinaryDownloadPlan.detectPlatform("Linux", null))
    }

    @Test
    fun `mac falls back to x64 for unknown arch`() {
        // Unrecognised arch on mac defaults to x64 (the more common build).
        assertEquals(Platform.MAC_X64, ByBinaryDownloadPlan.detectPlatform("Mac OS X", "ppc"))
    }

    // --- normalizeVersion --------------------------------------------------

    @Test
    fun `normalizeVersion strips leading v`() {
        assertEquals("1.2.3", ByBinaryDownloadPlan.normalizeVersion("v1.2.3"))
    }

    @Test
    fun `normalizeVersion strips uppercase V`() {
        assertEquals("2.0.0", ByBinaryDownloadPlan.normalizeVersion("V2.0.0"))
    }

    @Test
    fun `normalizeVersion keeps bare version`() {
        assertEquals("3.4.5", ByBinaryDownloadPlan.normalizeVersion("3.4.5"))
    }

    @Test
    fun `normalizeVersion trims whitespace`() {
        assertEquals("1.0.0", ByBinaryDownloadPlan.normalizeVersion("  1.0.0  "))
    }

    @Test
    fun `normalizeVersion null yields default`() {
        assertEquals(ByBinaryDownloadPlan.DEFAULT_VERSION, ByBinaryDownloadPlan.normalizeVersion(null))
    }

    @Test
    fun `normalizeVersion blank yields default`() {
        assertEquals(ByBinaryDownloadPlan.DEFAULT_VERSION, ByBinaryDownloadPlan.normalizeVersion("   "))
    }

    // --- assetName ---------------------------------------------------------

    @Test
    fun `assetName mac has no extension`() {
        assertEquals("by-mac-arm64", ByBinaryDownloadPlan.assetName("by", Platform.MAC_ARM64))
    }

    @Test
    fun `assetName windows has exe extension`() {
        assertEquals("buff-windows-x64.exe", ByBinaryDownloadPlan.assetName("buff", Platform.WINDOWS_X64))
    }

    @Test
    fun `assetName linux variants`() {
        assertEquals("by-linux-x64", ByBinaryDownloadPlan.assetName("by", Platform.LINUX_X64))
        assertEquals("by-linux-arm64", ByBinaryDownloadPlan.assetName("by", Platform.LINUX_ARM64))
    }

    // --- downloadUrl -------------------------------------------------------

    @Test
    fun `downloadUrl builds full github url`() {
        assertEquals(
            "https://github.com/basedpython/basedpython/releases/download/v1.0.0/by-mac-arm64",
            ByBinaryDownloadPlan.downloadUrl("by", "1.0.0", Platform.MAC_ARM64),
        )
    }

    @Test
    fun `downloadUrl adds exe on windows`() {
        assertEquals(
            "https://github.com/basedpython/basedpython/releases/download/v2.1.0/buff-windows-x64.exe",
            ByBinaryDownloadPlan.downloadUrl("buff", "v2.1.0", Platform.WINDOWS_X64),
        )
    }

    @Test
    fun `downloadUrl uses default version when null`() {
        val url = ByBinaryDownloadPlan.downloadUrl("by", null, Platform.LINUX_X64)
        assertTrue(url.contains("/v${ByBinaryDownloadPlan.DEFAULT_VERSION}/"))
        assertTrue(url.endsWith("/by-linux-x64"))
    }

    @Test
    fun `downloadUrl honours custom base url and trims trailing slash`() {
        assertEquals(
            "https://example.com/dl/v1.0.0/by-linux-arm64",
            ByBinaryDownloadPlan.downloadUrl("by", "1.0.0", Platform.LINUX_ARM64, baseUrl = "https://example.com/dl/"),
        )
    }

    // --- executableFileName ------------------------------------------------

    @Test
    fun `executableFileName plain on posix`() {
        assertEquals("by", ByBinaryDownloadPlan.executableFileName("by", Platform.MAC_ARM64))
        assertEquals("buff", ByBinaryDownloadPlan.executableFileName("buff", Platform.LINUX_X64))
    }

    @Test
    fun `executableFileName adds exe on windows`() {
        assertEquals("by.exe", ByBinaryDownloadPlan.executableFileName("by", Platform.WINDOWS_X64))
    }

    // --- installDir / installPath ------------------------------------------

    @Test
    fun `installDir is home dot basedpython bin`() {
        val dir = ByBinaryDownloadPlan.installDir("/home/dev")
        assertTrue(dir.endsWith(java.nio.file.Paths.get(".basedpython", "bin")))
        assertTrue(dir.startsWith(java.nio.file.Paths.get("/home/dev")))
    }

    @Test
    fun `installPath posix has no extension`() {
        val p = ByBinaryDownloadPlan.installPath("/home/dev", "by", Platform.MAC_ARM64)
        assertEquals("by", p.fileName.toString())
        assertTrue(p.parent.endsWith(java.nio.file.Paths.get(".basedpython", "bin")))
    }

    @Test
    fun `installPath windows has exe extension`() {
        val p = ByBinaryDownloadPlan.installPath("C:\\Users\\dev", "buff", Platform.WINDOWS_X64)
        assertEquals("buff.exe", p.fileName.toString())
    }

    // --- constants / invariants --------------------------------------------

    @Test
    fun `binary names are by and buff`() {
        assertEquals(listOf("by", "buff"), ByBinaryDownloadPlan.BINARY_NAMES)
    }

    @Test
    fun `only windows platform carries an exe suffix`() {
        for (p in Platform.values()) {
            if (p.windows) assertEquals(".exe", p.exe) else assertEquals("", p.exe)
        }
    }

    @Test
    fun `every platform slug is unique and non-blank`() {
        val slugs = Platform.values().map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(slugs.all { it.isNotBlank() })
    }
}
