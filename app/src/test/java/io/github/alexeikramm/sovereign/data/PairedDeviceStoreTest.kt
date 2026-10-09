package io.github.alexeikramm.sovereign.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Keystore-wrapped registry's storage and lookup (Chunk 2b). The real [KeystoreSecretCipher] is
 * Android-only, so a reversible XOR stand-in is used here; the last test proves the store wraps the
 * bytes before writing them, which is the property the Keystore cipher provides on device.
 */
class PairedDeviceStoreTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher (XOR is its own inverse). */
    private class XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }

    private fun store() = PairedDeviceStore(temp.root, XorCipher())

    private fun device(id: String, token: String) =
        PairedDevice(deviceId = id, name = "Desktop $id", token = token, pairedAtMs = 1L)

    @Test
    fun `upsert round-trips and replaces by deviceId`() {
        val store = store()
        store.upsert(device("a", "tok-a"))
        store.upsert(device("b", "tok-b"))
        store.upsert(device("a", "tok-a2"))

        val devices = store.load()
        assertEquals(2, devices.size)
        assertEquals(listOf("b", "a"), devices.map { it.deviceId })
        assertEquals("tok-a2", devices.first { it.deviceId == "a" }.token)

        // A second store instance reads the same file, so the screen and the service agree.
        assertEquals(devices, store().load())
    }

    @Test
    fun `setEnabled and remove persist`() {
        val store = store()
        store.upsert(device("a", "tok-a"))

        store.setEnabled("a", false)
        assertFalse(store().load().single().enabled)

        store.remove("a")
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `authenticate matches only the exact enabled token`() {
        val store = store()
        store.upsert(device("a", "tok-a"))

        assertEquals("a", store.authenticate("a", "tok-a")?.deviceId)
        assertNull(store.authenticate("a", "wrong"))
        assertNull(store.authenticate("other", "tok-a"))
        assertTrue(store.hasAnyEnabled())

        store.setEnabled("a", false)
        assertNull(store.authenticate("a", "tok-a"))
        assertFalse(store.hasAnyEnabled())
    }

    @Test
    fun `matchToken matches a disabled device while authenticate refuses it`() {
        val store = store()
        store.upsert(device("a", "tok-a"))
        store.setEnabled("a", false)

        assertEquals("a", store.matchToken("a", "tok-a")?.deviceId)
        assertNull(store.matchToken("a", "wrong"))
        assertNull(store.matchToken("other", "tok-a"))
        assertNull(store.authenticate("a", "tok-a"))
    }

    @Test
    fun `the token is never written in the clear`() {
        val token = "TOKEN-SENTINEL-0f8a1c27"
        store().upsert(device("a", token))

        val raw = File(temp.root, PairedDeviceStore.FILE_NAME).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("the wrapped registry must not contain the token", raw.contains(token))
        assertFalse(raw.contains("tok-a"))
    }

    @Test
    fun `a missing or corrupt file reads as empty`() {
        assertTrue(store().load().isEmpty())

        File(temp.root, PairedDeviceStore.FILE_NAME).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store().load().isEmpty())
    }
}
