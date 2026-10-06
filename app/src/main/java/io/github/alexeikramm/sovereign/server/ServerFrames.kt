package io.github.alexeikramm.sovereign.server

import java.util.UUID

/**
 * The transport-level policy for one inbound text frame (PROTOCOL.md §1/§3/§8): decode it and decide
 * whether to forward it to the connection's engine, ignore it, or answer with a fatal `ERROR` and
 * close.
 *
 * Kept separate from the socket so the policy is pure-JVM testable: the `SovereignServerService`
 * only performs the [Disposition]. The parse-time [ProtocolFault]s are mapped to the wire
 * [ErrorCode]s here (they are deliberately distinct enums).
 */
object ServerFrames {

    sealed interface Disposition {
        /** A catalogued message: hand it to the connection's protocol engine. */
        data class Forward(val message: ServerMessage) : Disposition

        /** An unknown `type`: ignored, not an error (PROTOCOL.md §3). */
        data object Ignore : Disposition

        /**
         * The frame cannot be used. [error] is the wire envelope to send back; [close] says whether
         * the connection must then be closed (fatal faults close, a malformed frame does not).
         */
        data class Fail(val error: ServerMessage, val close: Boolean) : Disposition
    }

    fun disposition(
        text: String,
        newId: () -> String = { UUID.randomUUID().toString() },
        now: () -> Long = { System.currentTimeMillis() },
    ): Disposition = when (val decoded = ServerProtocol.decode(text)) {
        is DecodeResult.Decoded -> Disposition.Forward(decoded.message)
        is DecodeResult.Ignored -> Disposition.Ignore
        is DecodeResult.Rejected -> {
            val code: ErrorCode
            val fatal: Boolean
            val close: Boolean
            when (decoded.fault) {
                ProtocolFault.TOO_LARGE -> {
                    code = ErrorCode.TOO_LARGE; fatal = true; close = true
                }
                ProtocolFault.PROTO_VERSION -> {
                    code = ErrorCode.PROTO_VERSION; fatal = true; close = true
                }
                // A malformed/partial frame from an authenticated peer is not fatal: keep the
                // connection and let the client retry. PROTOCOL.md §8 has no "malformed" code, so
                // INTERNAL is the honest wire answer.
                ProtocolFault.MALFORMED_ENVELOPE,
                ProtocolFault.MISSING_FIELD,
                ProtocolFault.BAD_PAYLOAD,
                -> {
                    code = ErrorCode.INTERNAL; fatal = false; close = false
                }
            }
            Disposition.Fail(
                error = ServerMessage(
                    MessageType.ERROR,
                    newId(),
                    now(),
                    ErrorPayload(code, decoded.detail, fatal),
                ),
                close = close,
            )
        }
    }
}
