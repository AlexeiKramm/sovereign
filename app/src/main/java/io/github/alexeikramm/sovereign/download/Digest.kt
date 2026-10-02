package io.github.alexeikramm.sovereign.download

import java.io.File
import java.security.MessageDigest

/**
 * Lowercase hex sha256 of a file. Every artifact is verified against a digest pinned in its spec,
 * and both the speech and cleanup downloads must compute it the same way, so it lives here rather
 * than in either model package.
 */
internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
