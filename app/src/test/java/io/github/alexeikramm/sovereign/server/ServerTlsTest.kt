package io.github.alexeikramm.sovereign.server

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned-TLS identity (Chunk 1b): it must be generated once, persisted, and derived to the same
 * SPKI fingerprint on every later run — a fresh fingerprint would silently invalidate every desktop's
 * pin (PROTOCOL.md §1).
 */
class ServerTlsTest {

    private fun tempDir() = Files.createTempDirectory("sovereign-tls").toFile()

    @Test
    fun `generates a self-signed identity and persists it`() {
        val dir = tempDir()

        val first = ServerTls.loadOrCreate(dir)

        assertTrue("PKCS12 file written", java.io.File(dir, ServerTls.FILE_NAME).isFile)
        assertNotNull(first.sslContext)
        // A self-signed certificate verifies against its own public key.
        first.certificate.verify(first.certificate.publicKey)
        // SHA-256 = 32 bytes -> 43 base64url characters, unpadded.
        assertEquals(43, first.spkiSha256.length)
    }

    @Test
    fun `reloading keeps the same certificate and fingerprint`() {
        val dir = tempDir()

        val first = ServerTls.loadOrCreate(dir)
        val second = ServerTls.loadOrCreate(dir)

        assertEquals(first.spkiSha256, second.spkiSha256)
        assertEquals(first.certificate, second.certificate)
    }

    @Test
    fun `distinct directories produce distinct identities`() {
        val a = ServerTls.loadOrCreate(tempDir())
        val b = ServerTls.loadOrCreate(tempDir())
        assertNotEquals(a.spkiSha256, b.spkiSha256)
    }
}
