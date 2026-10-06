package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDeviceStore
import io.github.alexeikramm.sovereign.data.SecretCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The shipped pairing authority (Chunk 2b): mutual-accept approval, the Keystore-wrapped registry and
 * the 5 → 60 s lockout (PROTOCOL.md §4). The dialog is a [PairingPrompter] fake and the clock is
 * injected, so the whole policy is exercised without Android.
 */
class RegistryPairingAuthorityTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private class XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)
    }

    private class FakePrompter(var answer: Boolean) : PairingPrompter {
        var prompts = 0
        override suspend fun prompt(request: PairingRequest): Boolean {
            prompts += 1
            return answer
        }
    }

    private var nowMs = 1_000_000L
    private var tokenCount = 0

    private fun store() = PairedDeviceStore(temp.root, XorCipher())

    private fun authority(prompter: PairingPrompter, store: PairedDeviceStore = store()) =
        RegistryPairingAuthority(
            serverSpki = "spki",
            store = store,
            prompter = prompter,
            now = { nowMs },
            newToken = { "token-${tokenCount++}" },
        )

    @Test
    fun `an approved pairing is persisted and authenticates`() = runTest {
        val prompter = FakePrompter(answer = true)
        val pairing = authority(prompter)

        assertEquals(PairingDecision.APPROVED, pairing.approve("desk-1", "Desktop"))
        val device = pairing.issue("desk-1", "Desktop")

        assertEquals("desk-1", pairing.authenticate("desk-1", device.token)?.deviceId)
        assertNull(pairing.authenticate("desk-1", "nope"))
        // Persisted: a fresh store (e.g. after the service restarts) still knows the device.
        assertEquals(device, store().load().single())
        assertEquals(1, prompter.prompts)
    }

    @Test
    fun `a denied pairing persists nothing and counts down attempts`() = runTest {
        val pairing = authority(FakePrompter(answer = false))

        assertEquals(PairingDecision.DENIED, pairing.approve("desk-1", "Desktop"))
        assertEquals(PairingPolicy.MAX_ATTEMPTS - 1, pairing.attemptsRemaining())
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `five failures lock pairing for sixty seconds`() = runTest {
        val prompter = FakePrompter(answer = false)
        val pairing = authority(prompter)

        repeat(PairingPolicy.MAX_ATTEMPTS) {
            assertEquals(PairingDecision.DENIED, pairing.approve("desk-1", "Desktop"))
        }

        assertEquals(0, pairing.attemptsRemaining())
        assertTrue(pairing.lockoutRemainingMs() > 0)
        // Locked: refused without asking the user again.
        assertEquals(PairingDecision.LOCKED, pairing.approve("desk-1", "Desktop"))
        assertEquals(PairingPolicy.MAX_ATTEMPTS, prompter.prompts)
    }

    @Test
    fun `pairing is allowed again after the lockout expires`() = runTest {
        val prompter = FakePrompter(answer = false)
        val pairing = authority(prompter)
        repeat(PairingPolicy.MAX_ATTEMPTS) { pairing.approve("desk-1", "Desktop") }

        nowMs += PairingPolicy.LOCKOUT_MS + 1

        assertEquals(0L, pairing.lockoutRemainingMs())
        assertEquals(PairingPolicy.MAX_ATTEMPTS, pairing.attemptsRemaining())
        assertEquals(PairingDecision.DENIED, pairing.approve("desk-1", "Desktop"))
        assertEquals(PairingPolicy.MAX_ATTEMPTS + 1, prompter.prompts)
    }

    @Test
    fun `a failed proof counts toward the lockout`() = runTest {
        val pairing = authority(FakePrompter(answer = true))

        repeat(PairingPolicy.MAX_ATTEMPTS) { pairing.recordFailedProof() }

        assertTrue(pairing.lockoutRemainingMs() > 0)
        assertEquals(PairingDecision.LOCKED, pairing.approve("desk-1", "Desktop"))
    }

    @Test
    fun `disabling and unpairing revoke authentication`() = runTest {
        val pairing = authority(FakePrompter(answer = true))
        pairing.approve("desk-1", "Desktop")
        val device = pairing.issue("desk-1", "Desktop")

        store().setEnabled("desk-1", false)
        assertNull(pairing.authenticate("desk-1", device.token))
        store().setEnabled("desk-1", true)
        assertEquals("desk-1", pairing.authenticate("desk-1", device.token)?.deviceId)

        store().remove("desk-1")
        assertNull(pairing.authenticate("desk-1", device.token))
    }
}
