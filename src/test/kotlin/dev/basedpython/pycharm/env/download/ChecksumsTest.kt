package dev.basedpython.pycharm.env.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ChecksumsTest {

    @Test
    fun `a file's digest is its SHA-256 in lowercase hex`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("abc"), "abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.sha256(file))
    }

    /** The shape uv's release assets use, measured: `<hex>  uv-aarch64-apple-darwin.tar.gz`. */
    @Test
    fun `a sha256sum file is read for its leading digest`() {
        val hex = "7e6ddb9316acc00f2296c82ff4d99977870ee34b2f0ddcae9444d714db9364ed"
        assertEquals(hex, Checksums.parseSha256File("$hex  uv-aarch64-apple-darwin.tar.gz\n"))
        assertEquals(hex, Checksums.parseSha256File("${hex.uppercase()} *uv.zip\r\n"))
        assertEquals(hex, Checksums.parseSha256File(hex))
    }

    @Test
    fun `a file with no digest in it has none`() {
        assertNull(Checksums.parseSha256File("Not Found"))
        assertNull(Checksums.parseSha256File(""))
        assertNull(Checksums.parseSha256File("abc123  file"))
    }
}
