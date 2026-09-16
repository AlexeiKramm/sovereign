package fi.ak.smartvoice.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.math.roundToLong

/**
 * DTOs for the frozen transport contract in `local-voice-stack/docs/CONTRACT.md` (v1).
 *
 * The app consumes that contract and never redefines it — see this repo's `docs/contract.md`.
 * Keys are always present in the 200 response, but the contract permits additive keys, so the
 * parser ignores unknown ones and tolerates missing optionals.
 */

@Serializable
data class LatencyMs(
    // Doubles on purpose: the frozen contract's example shows integers (`"stt": 1800`), but the
    // server emits fractional milliseconds (`665.6670000000133`). Double parses both, so the
    // client stays correct whichever way the server reports it.
    val stt: Double = 0.0,
    val cleanup: Double? = null,
    val total: Double = 0.0,
) {
    fun sttMs(): Long = stt.roundToLong()
    fun cleanupMs(): Long? = cleanup?.roundToLong()
    fun totalMs(): Long = total.roundToLong()
}

@Serializable
data class Correction(val from: String, val to: String)

@Serializable
data class TranscribeResponse(
    val text: String,
    @SerialName("stages_run") val stagesRun: List<String> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    @SerialName("latency_ms") val latencyMs: LatencyMs? = null,
    val warnings: List<String> = emptyList(),
    @SerialName("detected_language") val detectedLanguage: String? = null,
) {
    val cleanupRan: Boolean get() = stagesRun.contains(STAGE_CLEANUP)

    /**
     * The contract's degradation path: HTTP 200, raw text, `cleanup_unavailable` in warnings.
     * This is a normal state, not a failure — the UI says "cleanup skipped", never "error".
     */
    val cleanupUnavailable: Boolean get() = warnings.contains(WARNING_CLEANUP_UNAVAILABLE)

    companion object {
        const val STAGE_CLEANUP = "cleanup"
        const val WARNING_CLEANUP_UNAVAILABLE = "cleanup_unavailable"
    }
}

@Serializable
data class ApiErrorBody(val code: String, val message: String)

/** 400/413/501/500 use `{"error":{...}}`; 422 keeps FastAPI's `{"detail":...}` shape (§3). */
@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
    val detail: JsonElement? = null,
)

@Serializable
data class MetaResponse(@SerialName("contract_version") val contractVersion: String)

/** The contract version this client is written against; a mismatch refuses the remote tier. */
const val EXPECTED_CONTRACT_VERSION = "1.0"

val StackJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Error codes the contract defines (§3) mapped to something a person can act on. */
fun humanMessage(code: String, fallback: String): String = when (code) {
    "bad_input" -> "Server rejected the audio as malformed. This is a client bug, not bad speech."
    "audio_too_long" -> "Clip is longer than the server allows (max_audio_seconds)."
    "upload_too_large" -> "Upload exceeded max_upload_mb. Record shorter clips."
    "not_implemented" -> "Server returned 501 for an endpoint v1 does not implement."
    "internal" -> "Server hit an unexpected error. Check its logs."
    else -> fallback
}
