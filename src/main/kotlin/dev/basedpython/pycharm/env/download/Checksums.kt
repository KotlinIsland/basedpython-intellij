package dev.basedpython.pycharm.env.download

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Checking a download is the file its publisher says it is. */
object Checksums {

    /** The lowercase hex SHA-256 of [file]. */
    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The digest in a `sha256sum`-style file — `<hex>  <name>` — or null when it holds none.
     *
     * Only the leading token is read: the name after it is the publisher's, and a binary-mode `*`
     * or a Windows line ending changes nothing about the digest.
     */
    fun parseSha256File(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.matches(SHA256) }?.lowercase()

    private val SHA256 = Regex("[0-9a-fA-F]{64}")
}
