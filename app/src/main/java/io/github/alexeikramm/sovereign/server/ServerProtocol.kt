package io.github.alexeikramm.sovereign.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The frozen phone ↔ desktop wire codec, **v1**.
 *
 * Machine-checked against the golden vectors in
 * `fixtures/phone-server-desktop/protocol-vectors/` (see `ServerProtocolTest`). The separate desktop
 * repository re-implements the same contract in Go and runs the same vectors.
 *
 * Scope: envelope + message catalogue + parse-time validation only. Transport (TLS, WebSocket, mDNS),
 * session state, and auth enforcement live above this and are Chunk 1's job. Nothing here touches
 * Android, so it is pure-JVM testable.
 */
object ServerProtocol {

    /** Wire version. A mismatch is refused with [ProtocolFault.PROTO_VERSION]. */
    const val VERSION = 1

    /** Maximum UTF-8 size of one application frame (PROTOCOL.md §1/§9). */
    const val MAX_FRAME_BYTES = 256 * 1024

    /** `PROVISIONAL_TEXT.text` is truncated by the server above this length (PROTOCOL.md §9). */
    const val MAX_PROVISIONAL_CHARS = 8 * 1024

    /** Default WebSocket port; the authoritative value is carried by mDNS SRV (PROTOCOL.md §1). */
    const val DEFAULT_PORT = 8765

    /** DNS-SD service type (PROTOCOL.md §2). */
    const val SERVICE_TYPE = "_sovereign._tcp.local"

    /** WebSocket path (PROTOCOL.md §1). */
    const val PATH = "/sovereign"

    internal val json: Json = Json {
        // §3: unknown fields are ignored for forward compatibility.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Encodes a catalogued message into its envelope form. */
    fun encode(message: ServerMessage): String {
        // `serializer` is `KSerializer<out MessagePayload>`; the cast re-pairs it with the value it
        // encodes. Safe because `ServerMessage` is only produced by `decode` (which pairs the two
        // from the same `type`) or by a caller that constructed a matching payload.
        @Suppress("UNCHECKED_CAST")
        val payload = json.encodeToJsonElement(
            message.type.serializer as KSerializer<MessagePayload>,
            message.payload,
        )
        return buildJsonObject {
            put("v", VERSION)
            put("type", message.type.wire)
            put("id", message.id)
            put("ts", message.ts)
            put("payload", payload)
        }.toString()
    }

    /**
     * Parses a frame. Never throws for wire-level problems: unknown message types are [DecodeResult.Ignored]
     * (§3), and malformed/refused frames are [DecodeResult.Rejected] with a [ProtocolFault].
     */
    fun decode(text: String): DecodeResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
            return DecodeResult.Rejected(ProtocolFault.TOO_LARGE, "frame exceeds $MAX_FRAME_BYTES bytes")
        }

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "not valid JSON")
        val obj = root as? JsonObject
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "envelope is not a JSON object")

        val v = (obj["v"] as? JsonPrimitive)?.intOrNull
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "missing or non-integer `v`")
        if (v != VERSION) {
            return DecodeResult.Rejected(ProtocolFault.PROTO_VERSION, "protocol version $v != $VERSION")
        }

        val typeWire = (obj["type"] as? JsonPrimitive)?.contentOrNull
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "missing `type`")
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "missing `id`")
        val ts = (obj["ts"] as? JsonPrimitive)?.longOrNull
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "missing or non-integer `ts`")
        val payloadElement = obj["payload"]
            ?: return DecodeResult.Rejected(ProtocolFault.MALFORMED_ENVELOPE, "missing `payload`")

        val type = MessageType.fromWire(typeWire)
            ?: return DecodeResult.Ignored(typeWire) // §3: ignore, do not error.

        val payload = try {
            json.decodeFromJsonElement(type.serializer, payloadElement)
        } catch (e: MissingFieldException) {
            return DecodeResult.Rejected(ProtocolFault.MISSING_FIELD, e.message ?: "missing payload field")
        } catch (e: SerializationException) {
            return DecodeResult.Rejected(ProtocolFault.BAD_PAYLOAD, e.message ?: "bad payload")
        }

        return DecodeResult.Decoded(ServerMessage(type, id, ts, payload))
    }
}

/** Result of [ServerProtocol.decode]. */
sealed interface DecodeResult {

    /** A catalogued message. */
    data class Decoded(val message: ServerMessage) : DecodeResult

    /**
     * An unknown `type`. Per PROTOCOL.md §3 a v1 receiver MUST ignore it — this is not an error.
     */
    data class Ignored(val type: String) : DecodeResult

    /** A frame that cannot be acted on, with the reason. */
    data class Rejected(val fault: ProtocolFault, val detail: String) : DecodeResult
}

/** Parse-time rejection reasons. Distinct from the on-wire `ERROR.code` values (PROTOCOL.md §8). */
enum class ProtocolFault {
    /** `v` differs from [ServerProtocol.VERSION]. */
    PROTO_VERSION,

    /** Frame over [ServerProtocol.MAX_FRAME_BYTES]. */
    TOO_LARGE,

    /** Not a JSON object, or a missing/invalid required envelope key. */
    MALFORMED_ENVELOPE,

    /** A required payload field is absent. */
    MISSING_FIELD,

    /** A payload field has the wrong JSON type or an unknown enum value. */
    BAD_PAYLOAD,
}

/** One decoded catalogued message, without its envelope version. */
data class ServerMessage(
    val type: MessageType,
    val id: String,
    val ts: Long,
    val payload: MessagePayload,
)

/** Which peer sends a message. Documentation + vector metadata; not enforced by the codec. */
enum class Direction { C2S, S2C }

/**
 * The v1 message catalogue (PROTOCOL.md §4–§6). [wire] is the envelope `type` value; [serializer] is
 * the payload codec used by [ServerProtocol].
 */
enum class MessageType(
    val wire: String,
    val direction: Direction,
    internal val serializer: KSerializer<out MessagePayload>,
) {
    PAIR_HELLO("PAIR_HELLO", Direction.C2S, PairHelloPayload.serializer()),
    PAIR_CHALLENGE("PAIR_CHALLENGE", Direction.S2C, PairChallengePayload.serializer()),
    PAIR_RESPONSE("PAIR_RESPONSE", Direction.S2C, PairResponsePayload.serializer()),
    PAIR_DONE("PAIR_DONE", Direction.C2S, PairDonePayload.serializer()),
    PAIR_FAIL("PAIR_FAIL", Direction.S2C, PairFailPayload.serializer()),

    HELLO("HELLO", Direction.C2S, HelloPayload.serializer()),
    WELCOME("WELCOME", Direction.S2C, WelcomePayload.serializer()),
    AUTH_FAIL("AUTH_FAIL", Direction.S2C, AuthFailPayload.serializer()),

    START_DICTATION("START_DICTATION", Direction.C2S, StartDictationPayload.serializer()),
    START_ACK("START_ACK", Direction.S2C, StartAckPayload.serializer()),
    SESSION_BUSY("SESSION_BUSY", Direction.S2C, SessionBusyPayload.serializer()),
    STOP_DICTATION("STOP_DICTATION", Direction.C2S, StopDictationPayload.serializer()),
    CANCEL_DICTATION("CANCEL_DICTATION", Direction.C2S, CancelDictationPayload.serializer()),
    TAKEOVER("TAKEOVER", Direction.C2S, TakeoverPayload.serializer()),
    SESSION_RELEASED("SESSION_RELEASED", Direction.S2C, SessionReleasedPayload.serializer()),
    STATE_CHANGED("STATE_CHANGED", Direction.S2C, StateChangedPayload.serializer()),
    SERVER_STATE("SERVER_STATE", Direction.S2C, ServerStatePayload.serializer()),
    PROVISIONAL_TEXT("PROVISIONAL_TEXT", Direction.S2C, ProvisionalTextPayload.serializer()),
    COMMIT_TEXT("COMMIT_TEXT", Direction.S2C, CommitTextPayload.serializer()),
    UTTERANCE_DONE("UTTERANCE_DONE", Direction.S2C, UtteranceDonePayload.serializer()),
    ERROR("ERROR", Direction.S2C, ErrorPayload.serializer()),

    PING("PING", Direction.C2S, PingPayload.serializer()),
    PONG("PONG", Direction.S2C, PongPayload.serializer());

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(wire: String): MessageType? = byWire[wire]
    }
}

/** Marker for every catalogued payload. */
sealed interface MessagePayload

// --- Pairing (PROTOCOL.md §4) -----------------------------------------------------

@Serializable
data class PairHelloPayload(val deviceId: String, val name: String, val protover: Int) : MessagePayload

@Serializable
data class PairChallengePayload(val serverNonce: String, val serverSpki: String) : MessagePayload

@Serializable
data class PairResponsePayload(val deviceId: String, val serverName: String, val token: String) : MessagePayload

@Serializable
data class PairDonePayload(val proof: String) : MessagePayload

@Serializable
data class PairFailPayload(val reason: PairFailReason, val attemptsLeft: Int) : MessagePayload

@Serializable
enum class PairFailReason { DENIED, LOCKED, VERSION }

// --- Authentication (PROTOCOL.md §5) ----------------------------------------------

@Serializable
data class HelloPayload(
    val token: String,
    val deviceId: String,
    val protover: Int,
    val capabilities: List<String>,
) : MessagePayload

@Serializable
data class WelcomePayload(
    val protover: Int,
    val deviceId: String,
    val serverName: String,
    val capabilities: List<String>,
    val maxFrameBytes: Int,
) : MessagePayload

@Serializable
data class AuthFailPayload(val reason: AuthFailReason) : MessagePayload

@Serializable
enum class AuthFailReason { BAD_TOKEN, DISABLED, PROTO_VERSION }

// --- Dictation session (PROTOCOL.md §6) -------------------------------------------

@Serializable
data class StartDictationPayload(val requestId: String, val mode: DictationMode) : MessagePayload

@Serializable
enum class DictationMode { HOLD, TOGGLE }

@Serializable
data class StartAckPayload(val sessionId: String) : MessagePayload

@Serializable
data class SessionBusyPayload(val holderDeviceId: String, val holderName: String, val canTakeover: Boolean) : MessagePayload

@Serializable
data class StopDictationPayload(val sessionId: String) : MessagePayload

@Serializable
data class CancelDictationPayload(val sessionId: String) : MessagePayload

@Serializable
data class TakeoverPayload(val sessionId: String, val force: Boolean) : MessagePayload

@Serializable
data class SessionReleasedPayload(val sessionId: String, val reason: ReleaseReason) : MessagePayload

@Serializable
enum class ReleaseReason { STOPPED, CANCELLED, TAKEOVER, TIMEOUT, ERROR }

@Serializable
data class StateChangedPayload(val sessionId: String, val state: PipelineState) : MessagePayload

/**
 * Unsolicited pipeline availability (PROTOCOL.md §6, v1.1 addendum). Sent after `WELCOME` and on every
 * arbiter change. `RESERVED` is a phone-local capture; `BUSY` is a desktop session (possibly another
 * desktop). Added by CHUNK-8; only the arbiter's *holder* is known process-wide, so a `BUSY` holder
 * cannot yet be named per-connection (see `serverStatePayload`).
 */
@Serializable
data class ServerStatePayload(
    val state: ServerState,
    val holderDeviceId: String,
    val holderName: String,
) : MessagePayload

@Serializable
enum class ServerState { IDLE, RESERVED, BUSY }

@Serializable
enum class PipelineState { WARMING, LISTENING, PROCESSING, IDLE, CANCELLED, ERROR }

@Serializable
data class ProvisionalTextPayload(val sessionId: String, val seq: Long, val text: String) : MessagePayload

@Serializable
data class CommitTextPayload(val sessionId: String, val seq: Long, val text: String, val perf: CommitPerf) : MessagePayload

/** `asrMs` / `cleanupMs` for the unit that produced the snapshot (PROTOCOL.md §6). */
@Serializable
data class CommitPerf(val asrMs: Long, val cleanupMs: Long)

@Serializable
data class UtteranceDonePayload(
    val sessionId: String,
    val deliveryText: String,
    val rawText: String,
    val reason: UtteranceDoneReason,
) : MessagePayload

@Serializable
enum class UtteranceDoneReason { FINAL, CANCELLED, ABORTED, ERROR }

@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val message: String,
    val fatal: Boolean,
    val sessionId: String? = null,
) : MessagePayload

/** On-wire error codes (PROTOCOL.md §8). */
@Serializable
enum class ErrorCode {
    @SerialName("PROTO_VERSION") PROTO_VERSION,
    @SerialName("AUTH_REQUIRED") AUTH_REQUIRED,
    @SerialName("AUTH_FAILED") AUTH_FAILED,
    @SerialName("SESSION_BUSY") SESSION_BUSY,
    @SerialName("SESSION_UNKNOWN") SESSION_UNKNOWN,
    @SerialName("MIC_BUSY") MIC_BUSY,
    @SerialName("ENGINE_UNAVAILABLE") ENGINE_UNAVAILABLE,
    @SerialName("TOO_LARGE") TOO_LARGE,
    @SerialName("RATE_LIMITED") RATE_LIMITED,
    @SerialName("INTERNAL") INTERNAL,
}

// --- Ops (PROTOCOL.md §6) ---------------------------------------------------------

@Serializable
data class PingPayload(val clientTs: Long) : MessagePayload

@Serializable
data class PongPayload(val clientTs: Long, val serverTs: Long) : MessagePayload
