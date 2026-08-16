package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which archive the one-click uv install fetches, for every platform.
 *
 * Every asset name here was checked against uv's release page: these are the target triples
 * `github.com/astral-sh/uv/releases/latest/download/uv-<triple>.<ext>` actually serves. A typo in
 * one of them is a 404 on a machine the developer does not have, which is precisely the failure a
 * test can catch and a manual check cannot.
 */
class UvDownloadTest {

    private fun url(os: String, arch: String): String? = UvDownload.plan(os, arch)?.url

    @Test
    fun `macOS gets the apple-darwin tarballs`() {
        assertEquals("${UvDownload.BASE_URL}/uv-aarch64-apple-darwin.tar.gz", url("Mac OS X", "aarch64"))
        assertEquals("${UvDownload.BASE_URL}/uv-x86_64-apple-darwin.tar.gz", url("Mac OS X", "x86_64"))
        // `Darwin` and `os x` both appear as os.name across JVM builds.
        assertEquals(url("Mac OS X", "aarch64"), url("Darwin", "arm64"))
    }

    @Test
    fun `Linux gets the gnu tarballs`() {
        assertEquals("${UvDownload.BASE_URL}/uv-x86_64-unknown-linux-gnu.tar.gz", url("Linux", "amd64"))
        assertEquals("${UvDownload.BASE_URL}/uv-aarch64-unknown-linux-gnu.tar.gz", url("Linux", "aarch64"))
    }

    @Test
    fun `Windows gets the msvc zips`() {
        assertEquals("${UvDownload.BASE_URL}/uv-x86_64-pc-windows-msvc.zip", url("Windows 11", "amd64"))
        assertEquals("${UvDownload.BASE_URL}/uv-aarch64-pc-windows-msvc.zip", url("Windows 11", "aarch64"))
    }

    /** `aarch64` also satisfies the 64-bit test, so checking the width first calls every ARM box x64. */
    @Test
    fun `ARM is decided before the register width`() {
        assertTrue(url("Windows 11", "aarch64")!!.contains("aarch64"))
        assertTrue(url("Linux", "arm64")!!.contains("aarch64"))
    }

    @Test
    fun `the archive kind and member follow the platform`() {
        val mac = UvDownload.plan("Mac OS X", "aarch64")!!
        assertEquals(EnvToolDownload.ArchiveKind.TAR_GZ, mac.archive)
        assertEquals("uv", mac.memberSuffix)
        assertEquals("uv", mac.fileName)

        val windows = UvDownload.plan("Windows 11", "amd64")!!
        assertEquals(EnvToolDownload.ArchiveKind.ZIP, windows.archive)
        assertEquals("uv.exe", windows.memberSuffix)
        assertEquals("uv.exe", windows.fileName)
    }

    /**
     * An unsupported platform returns null rather than guessing, so the UI can say "install it
     * yourself" instead of offering a button that downloads a 404.
     */
    @Test
    fun `a platform uv does not publish for has no plan`() {
        assertNull(UvDownload.plan("SunOS", "sparc"))
        assertNull(UvDownload.plan("Linux", "i386"))
        assertNull(UvDownload.plan(null, "aarch64"))
        assertNull(UvDownload.plan("Linux", null))
    }
}
