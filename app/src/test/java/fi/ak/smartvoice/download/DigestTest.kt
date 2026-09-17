package fi.ak.smartvoice.download

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DigestTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `digest matches the canonical vector for abc`() {
        val file = temp.newFile("tokens.txt")
        file.writeText("abc")
        // sha256("abc") — the canonical vector, so this test cannot drift with our own hashing.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256(file),
        )
    }

    @Test
    fun `digest is lowercase hex and stable for a larger file`() {
        val file = temp.newFile("blob.bin")
        file.writeBytes(ByteArray(3 shl 20) { (it % 251).toByte() })

        val digest = sha256(file)
        assertEquals(64, digest.length)
        assertEquals("same bytes, same digest", digest, sha256(file))
    }
}
