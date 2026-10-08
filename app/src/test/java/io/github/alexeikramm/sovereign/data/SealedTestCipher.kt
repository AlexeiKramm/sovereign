package io.github.alexeikramm.sovereign.data

/**
 * Reversible, unsealed-input-rejecting stand-in for the Keystore cipher, for JVM unit tests where
 * AndroidKeyStore is unavailable. It rejects input without its magic prefix — exactly as the real
 * cipher fails to decrypt plaintext — so the legacy-plaintext import path is exercised.
 */
internal class SealedTestCipher : SecretCipher {

    override fun encrypt(plain: ByteArray): ByteArray =
        MAGIC + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= MAGIC.size && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "input is not sealed"
        }
        val body = blob.copyOfRange(MAGIC.size, blob.size)
        return ByteArray(body.size) { (body[it].toInt() xor 0x5A).toByte() }
    }

    private companion object {
        val MAGIC = byteArrayOf(0x53, 0x31, 0x00)
    }
}
