package io.github.alexeikramm.sovereign.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wraps and unwraps the registry bytes. Production uses [KeystoreSecretCipher]; unit tests supply a
 * reversible fake so the registry logic can be exercised on the JVM (there is no Keystore there).
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable **Android Keystore** key, so the bearer token is wrapped at
 * rest (PROTOCOL.md §10, D-107). The Keystore key is created on first use; it survives
 * `adb install -r` and is destroyed on uninstall, which is the intended "forget every pairing"
 * behaviour. The IV is random per write and prepended to the ciphertext.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "wrapped registry is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "sovereign_paired_devices"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The persisted paired-desktop registry (Chunk 2b): view, enable/disable and unpair (PROTOCOL.md §4).
 * Stored as a single wrapped JSON file, so the token never reaches disk in the clear (D-107).
 *
 * Safe to instantiate more than once — the Server screen and the service each hold one. Every call
 * reads the file and writes are atomic, so the two see each other's changes.
 */
class PairedDeviceStore(
    private val dir: File,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(dir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    /** The registry in issue order; empty if nothing is paired or the file cannot be read. */
    @Synchronized
    fun load(): List<PairedDevice> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString<PairedDeviceRegistryData>(
                cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8),
            ).devices
        } catch (_: Exception) {
            // A corrupt or undecryptable file (e.g. the Keystore key was lost) must not brick
            // pairing: report nothing and let the desktop pair again.
            emptyList()
        }
    }

    /** Adds [device], replacing any existing record for the same [PairedDevice.deviceId]. */
    @Synchronized
    fun upsert(device: PairedDevice) =
        save(load().filterNot { it.deviceId == device.deviceId } + device)

    @Synchronized
    fun setEnabled(deviceId: String, enabled: Boolean) =
        save(load().map { if (it.deviceId == deviceId) it.copy(enabled = enabled) else it })

    /** Unpairs: deletes the phone's copy of the token (the desktop keeps its stale one until it
     *  reconnects and is refused, PROTOCOL.md §10). */
    @Synchronized
    fun remove(deviceId: String) =
        save(load().filterNot { it.deviceId == deviceId })

    /** The **enabled** device whose id and token match, else null (constant-time token compare). */
    @Synchronized
    fun authenticate(deviceId: String, token: String): PairedDevice? =
        load().firstOrNull {
            it.deviceId == deviceId && it.enabled &&
                MessageDigest.isEqual(it.token.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
        }

    /** Whether any desktop is currently enabled — the mDNS `paired` hint (PROTOCOL.md §2). */
    @Synchronized
    fun hasAnyEnabled(): Boolean = load().any { it.enabled }

    private fun save(devices: List<PairedDevice>) {
        val bytes = cipher.encrypt(
            json.encodeToString(PairedDeviceRegistryData(devices)).toByteArray(Charsets.UTF_8),
        )
        dir.mkdirs()
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "paired_devices.bin"
    }
}
