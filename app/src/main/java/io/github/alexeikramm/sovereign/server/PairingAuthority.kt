package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.data.PairedDeviceStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The pairing-attempt policy both devices share (PROTOCOL.md §4, §9). */
object PairingPolicy {
    /** Failed/denied attempts before pairing is refused. */
    const val MAX_ATTEMPTS = 5

    /** How long pairing stays refused once the attempts are exhausted. */
    const val LOCKOUT_MS = 60_000L
}

/** The phone's answer to a pairing request, or a refusal because it is locked out. */
enum class PairingDecision { APPROVED, DENIED, LOCKED }

/**
 * What the phone asks the user to approve (PROTOCOL.md §4). [serverSpki] is the phone's **own**
 * certificate fingerprint, shown so the user can compare it with the one the desktop is about to
 * pin — the man-in-the-middle defence for the first, not-yet-pinned exchange.
 *
 * [id] is stamped by the prompter so the dialog's answer can be matched to the request it shows.
 */
data class PairingRequest(
    val deviceId: String,
    val name: String,
    val serverSpki: String,
    val id: Long = 0L,
)

/** Asks the user to approve a pairing; suspends until they answer or the request is cancelled. */
fun interface PairingPrompter {
    suspend fun prompt(request: PairingRequest): Boolean
}

/**
 * The pairing/authentication seam (PROTOCOL.md §4–§5, D-107).
 *
 * [RegistryPairingAuthority] is the shipped implementation: the mutual-accept dialog backed by the
 * persisted, Keystore-wrapped registry. Keeping the dialog behind [PairingPrompter] and the storage
 * behind [PairedDeviceStore] is what lets the protocol engine and this logic be tested without
 * Android or a device.
 */
interface PairingAuthority {

    /** Base64url SHA-256 of the server certificate's SPKI — advertised over mDNS and pinned (§4). */
    val serverSpki: String

    /**
     * Ask the user to approve a pairing request, suspending until they decide. Returns `LOCKED`
     * without prompting when a previous run of failures still holds the lockout.
     */
    suspend fun approve(deviceId: String, name: String): PairingDecision

    /** Issues and persists a fresh token for an approved device, replacing any previous one. */
    fun issue(deviceId: String, name: String): PairedDevice

    /** The device if [token] is valid and enabled, else null. */
    fun authenticate(deviceId: String, token: String): PairedDevice?

    /**
     * The device if [token] matches, **regardless of [PairedDevice.enabled]**, else null. It lets the
     * engine distinguish a known-but-disabled client (`AUTH_FAIL(DISABLED)`) from an unknown one
     * (`BAD_TOKEN`); implementations must still verify [token].
     */
    fun matchToken(deviceId: String, token: String): PairedDevice?

    /** Milliseconds until pairing is accepted again; 0 when not locked out. */
    fun lockoutRemainingMs(): Long

    /** Attempts left in the current window, for `PAIR_FAIL.attemptsLeft` (§4). */
    fun attemptsRemaining(): Int

    /** A `PAIR_DONE` proof failed; counts as an attempt toward the lockout. */
    fun recordFailedProof()
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
 * The shipped [PairingAuthority] (Chunk 2b): mutual-accept approval over the unpinned pairing
 * channel, backed by the persisted, Keystore-wrapped [PairedDeviceStore], with the 5 → 60 s lockout
 * (PROTOCOL.md §4). It replaces the Chunk-1b debug auto-accept entirely.
 *
 * Lockout state is deliberately process-local: it guards the live server, and a restart resetting it
 * is cheaper than persisting an attacker-controllable counter.
 */
class RegistryPairingAuthority(
    override val serverSpki: String,
    private val store: PairedDeviceStore,
    private val prompter: PairingPrompter,
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes))
    },
    /** Called after the registry changes, so a host can refresh its view. */
    private val onChanged: () -> Unit = {},
) : PairingAuthority {

    private var failures = 0
    private var lockedUntilMs = 0L

    override suspend fun approve(deviceId: String, name: String): PairingDecision {
        if (lockoutRemainingMs() > 0) return PairingDecision.LOCKED
        val approved = prompter.prompt(PairingRequest(deviceId, name, serverSpki))
        if (!approved) {
            recordFailure()
            return PairingDecision.DENIED
        }
        failures = 0
        lockedUntilMs = 0L
        return PairingDecision.APPROVED
    }

    override fun issue(deviceId: String, name: String): PairedDevice {
        val device = PairedDevice(deviceId, name, newToken(), enabled = true, pairedAtMs = now())
        store.upsert(device)
        onChanged()
        return device
    }

    override fun authenticate(deviceId: String, token: String): PairedDevice? =
        store.authenticate(deviceId, token)

    override fun matchToken(deviceId: String, token: String): PairedDevice? =
        store.matchToken(deviceId, token)

    override fun lockoutRemainingMs(): Long {
        val remaining = lockedUntilMs - now()
        if (remaining <= 0) {
            // The window elapsed: clear it so the next run of attempts starts fresh.
            if (lockedUntilMs != 0L) {
                lockedUntilMs = 0L
                failures = 0
            }
            return 0L
        }
        return remaining
    }

    override fun attemptsRemaining(): Int = (PairingPolicy.MAX_ATTEMPTS - failures).coerceAtLeast(0)

    override fun recordFailedProof() = recordFailure()

    private fun recordFailure() {
        failures += 1
        if (failures >= PairingPolicy.MAX_ATTEMPTS) {
            lockedUntilMs = now() + PairingPolicy.LOCKOUT_MS
        }
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
