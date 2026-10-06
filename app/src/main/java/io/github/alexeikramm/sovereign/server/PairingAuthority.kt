package io.github.alexeikramm.sovereign.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A desktop the phone has approved (PROTOCOL.md §4). */
data class PairedDevice(
    val deviceId: String,
    val name: String,
    val token: String,
    val enabled: Boolean = true,
)

/**
 * The pairing/authentication seam (PROTOCOL.md §4–§5, D-107).
 *
 * Chunk 1a ships [InMemoryPairingAuthority]; Chunk 2 backs the same interface with the mutual-accept
 * approval dialog and Keystore-wrapped token storage, and adds the timed lockout. Keeping it an
 * interface is what lets the protocol engine be tested without Android or a dialog.
 */
interface PairingAuthority {

    /** Base64url SHA-256 of the server certificate's SPKI — advertised over mDNS and pinned (§4). */
    val serverSpki: String

    /**
     * Ask the user to approve a pairing request, suspending until they decide. Chunk 2 shows the
     * dialog; tests supply a deterministic answer.
     */
    suspend fun approve(deviceId: String, name: String): Boolean

    /** Issues a fresh token for an approved device, replacing any previous one. */
    fun issue(deviceId: String, name: String): PairedDevice

    /** The device if [token] is valid and enabled, else null. */
    fun authenticate(deviceId: String, token: String): PairedDevice?
}

/**
 * `proof = HMAC-SHA256(token, serverNonce)` (PROTOCOL.md §4). The HMAC key is the **base64url token
 * string as transmitted**, not its decoded bytes — the one interpretation both sides can compute
 * from what crosses the wire.
 */
object PairingProof {

    fun compute(token: String, serverNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64(mac.doFinal(serverNonce.toByteArray(Charsets.UTF_8)))
    }

    /** Constant-time comparison, so a wrong proof cannot be distinguished from a slow one. */
    fun matches(token: String, serverNonce: String, proof: String): Boolean =
        MessageDigest.isEqual(
            compute(token, serverNonce).toByteArray(Charsets.UTF_8),
            proof.toByteArray(Charsets.UTF_8),
        )

    private fun base64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * In-memory [PairingAuthority]: tokens live only for the process lifetime. This is the 1a test/harness
 * implementation — **not** the shipped pairing flow. Chunk 2 replaces it with the approval dialog and
 * Keystore persistence, and adds the timed lockout that 1a does not have.
 */
class InMemoryPairingAuthority(
    override val serverSpki: String,
    private val approveResult: Boolean = true,
) : PairingAuthority {

    private val devices = LinkedHashMap<String, PairedDevice>()
    private val random = SecureRandom()

    override suspend fun approve(deviceId: String, name: String): Boolean = approveResult

    override fun issue(deviceId: String, name: String): PairedDevice {
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        return PairedDevice(deviceId, name, token).also { devices[deviceId] = it }
    }

    override fun authenticate(deviceId: String, token: String): PairedDevice? =
        devices[deviceId]?.takeIf {
            it.enabled && MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray())
        }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
