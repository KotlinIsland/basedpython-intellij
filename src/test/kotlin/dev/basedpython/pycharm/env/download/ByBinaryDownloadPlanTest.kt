package dev.basedpython.pycharm.env.download

import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `windows arm64 from Windows 11 aarch64`() {
        // Windows on ARM reports a 64-bit arch too, so the width test must not claim it first.
        assertEquals(Platform.WINDOWS_ARM64, ByBinaryDownloadPlan.detectPlatform("Windows 11", "aarch64"))
        assertEquals(Platform.WINDOWS_ARM64, ByBinaryDownloadPlan.detectPlatform("Windows 11", "arm64"))
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

    // --- the wheel on PyPI --------------------------------------------------

    private val sha = "a".repeat(64)

    private fun file(filename: String, yanked: Boolean = false, digest: String? = sha) = """
        {"filename": "$filename", "url": "https://files.example/$filename", "yanked": $yanked,
         "digests": {${digest?.let { "\"sha256\": \"$it\"" }.orEmpty()}}}
    """

    private fun releases(vararg versions: Pair<String, List<String>>) =
        """{"info": {"version": "0.0.0"}, "releases": {${versions.joinToString(",") { (v, files) -> "\"$v\": [${files.joinToString(",")}]" }}}}"""

    /** The file names basedpython 0.0.1a9 actually published, as the index lists them. */
    private val published = listOf(
        "basedpython-0.0.1a9-py3-none-linux_armv6l.whl",
        "basedpython-0.0.1a9-py3-none-macosx_10_12_x86_64.whl",
        "basedpython-0.0.1a9-py3-none-macosx_11_0_arm64.whl",
        "basedpython-0.0.1a9-py3-none-manylinux_2_17_aarch64.manylinux2014_aarch64.whl",
        "basedpython-0.0.1a9-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
        "basedpython-0.0.1a9-py3-none-musllinux_1_2_x86_64.whl",
        "basedpython-0.0.1a9-py3-none-win32.whl",
        "basedpython-0.0.1a9-py3-none-win_amd64.whl",
        "basedpython-0.0.1a9-py3-none-win_arm64.whl",
        "basedpython-0.0.1a9.tar.gz",
    )

    @Test
    fun `every supported platform finds exactly one of the wheels basedpython publishes`() {
        val expected = mapOf(
            Platform.MAC_ARM64 to "basedpython-0.0.1a9-py3-none-macosx_11_0_arm64.whl",
            Platform.MAC_X64 to "basedpython-0.0.1a9-py3-none-macosx_10_12_x86_64.whl",
            Platform.LINUX_X64 to "basedpython-0.0.1a9-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
            Platform.LINUX_ARM64 to "basedpython-0.0.1a9-py3-none-manylinux_2_17_aarch64.manylinux2014_aarch64.whl",
            Platform.WINDOWS_X64 to "basedpython-0.0.1a9-py3-none-win_amd64.whl",
            Platform.WINDOWS_ARM64 to "basedpython-0.0.1a9-py3-none-win_arm64.whl",
        )
        for (platform in Platform.values()) {
            assertEquals(listOf(expected[platform]), published.filter { platform.runsWheel(it) }, platform.name)
        }
    }

    /**
     * The index's own "latest" is a placeholder that skips pre-releases, and every real release so
     * far is one — so newest is decided by PEP 440 over every release, not taken from `info`.
     */
    @Test
    fun `the newest release with a wheel for the platform is chosen, pre-releases included`() {
        val json = releases(
            "0.0.0" to listOf(file("basedpython-0.0.0-py3-none-any.whl")),
            "0.0.1a8" to listOf(file("basedpython-0.0.1a8-py3-none-macosx_11_0_arm64.whl")),
            "0.0.1a10" to listOf(file("basedpython-0.0.1a10-py3-none-win_amd64.whl")),
            "0.0.1a9" to listOf(file("basedpython-0.0.1a9-py3-none-macosx_11_0_arm64.whl")),
        )

        val wheel = ByBinaryDownloadPlan.newestWheel(json, Platform.MAC_ARM64)
        assertEquals("0.0.1a9", wheel?.version, "a10 has nothing for this platform; 0.0.0 has no binaries")
        assertEquals("https://files.example/basedpython-0.0.1a9-py3-none-macosx_11_0_arm64.whl", wheel?.url)
        assertEquals(sha, wheel?.sha256)
    }

    @Test
    fun `a yanked wheel, or one with no digest to check, is never chosen`() {
        val json = releases(
            "2.0" to listOf(file("basedpython-2.0-py3-none-win_amd64.whl", yanked = true)),
            "1.5" to listOf(file("basedpython-1.5-py3-none-win_amd64.whl", digest = null)),
            "1.0" to listOf(file("basedpython-1.0-py3-none-win_amd64.whl")),
        )
        assertEquals("1.0", ByBinaryDownloadPlan.newestWheel(json, Platform.WINDOWS_X64)?.version)
    }

    @Test
    fun `an index answer that is not a release listing chooses nothing`() {
        assertNull(ByBinaryDownloadPlan.newestWheel("<html>", Platform.LINUX_X64))
        assertNull(ByBinaryDownloadPlan.newestWheel(releases(), Platform.LINUX_X64))
    }

    /** Both executables live under the wheel's data scripts directory, and nothing else matches. */
    @Test
    fun `the binaries are found under the wheel's scripts directory`() {
        assertTrue(ByBinaryDownloadPlan.isBinaryEntry("basedpython-0.0.1a9.data/scripts/by", "by", Platform.MAC_ARM64))
        assertTrue(ByBinaryDownloadPlan.isBinaryEntry("basedpython-0.0.1a9.data/scripts/buff.exe", "buff", Platform.WINDOWS_X64))
        assertFalse(ByBinaryDownloadPlan.isBinaryEntry("basedpython-0.0.1a9.data/scripts/buff", "by", Platform.MAC_ARM64))
        assertFalse(ByBinaryDownloadPlan.isBinaryEntry("basedpython-0.0.1a9.dist-info/RECORD", "by", Platform.MAC_ARM64))
        assertFalse(ByBinaryDownloadPlan.isBinaryEntry("basedpython-0.0.1a9.data/scripts/by", "by", Platform.WINDOWS_X64))
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
