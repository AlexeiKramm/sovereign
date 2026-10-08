package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.data.PairedDevice
import io.github.alexeikramm.sovereign.dictation.SessionArbiter
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The identity the phone presents to a desktop (`WELCOME`, `PAIR_RESPONSE`, mDNS). */
data class ServerIdentity(
    val deviceId: String,
    val serverName: String,
    val capabilities: List<String> = listOf("snapshot_commit", "provisional_text"),
)

/**
 * One WebSocket connection's protocol brain (PROTOCOL.md §4–§6): pairing, authentication, and the
 * dictation-session state machine, driving a [CaptureHost] and emitting typed [ServerMessage]s.
 *
 * The transport (Chunk 1b) decodes inbound frames and encodes the [sink] back to the socket, so this
 * class has no Android or socket dependency: it is unit-tested against fakes. It never constructs an
 * engine, recorder or dispatcher — the shared `DictationSession` is the only capture path (D-106).
 *
 * All outbound traffic goes through [sink] with a fresh `id` and `ts`. Suspend work (`finish`, pairing
 * approval) runs on [scope]; callers must not assume [onMessage] has completed it when it returns.
 */
class SovereignServerEngine(
    private val identity: ServerIdentity,
    private val pairing: PairingAuthority,
    private val capture: CaptureHost,
    private val scope: CoroutineScope,
    private val sink: (ServerMessage) -> Unit,
    private val random: SecureRandom = SecureRandom(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
    /** A desktop finished authenticating (`WELCOME`); the dashboard shows it as connected. */
    private val onAuthenticated: (PairedDevice) -> Unit = {},
    /** A `PING` arrived; feeds the dashboard's heartbeat-age readout. */
    private val onHeartbeat: () -> Unit = {},
    /**
     * The current pipeline availability (CHUNK-8), advertised to a client immediately after `WELCOME`
     * and read by the transport's broadcaster. Defaults to `IDLE` so a bare engine (and its tests)
     * need not know about the arbiter.
     */
    private val availability: () -> ServerStatePayload = {
        ServerStatePayload(ServerState.IDLE, "", "")
    },
) {

    /** Connection phase. `READY` means authenticated; session messages are refused before it. */
    enum class Phase { AWAITING_PAIRING, AWAITING_AUTH, READY, CLOSED }

    var phase: Phase = Phase.AWAITING_PAIRING
        private set

    /** The authenticated desktop, once [MessageType.HELLO] succeeds. */
    var authenticatedDevice: PairedDevice? = null
        private set

    private var pendingDeviceId: String? = null
    private var pendingToken: String? = null
    private var serverNonce: String? = null

    private var sessionId: String? = null
    private var sessionRequestId: String? = null
    private var stopping = false
    private var provisionalSeq = 0L
    private var commitSeq = 0L

    private val listener = object : CaptureListener {
        override fun onState(state: PipelineState) {
            if (state == PipelineState.ERROR) {
                // A capture that fails to come up leaves no session behind — the host has already
                // aborted and released the arbiter — so release the protocol session too. Without
                // this, sessionId stays set and every later START_DICTATION is answered against a
                // session that no longer exists.
                emitState(PipelineState.ERROR)
                sessionId?.let { endSession(it, ReleaseReason.ERROR) }
                return
            }
            emitState(state)
        }

        override fun onProvisional(text: String) {
            val sid = activeSessionId() ?: return
            provisionalSeq += 1
            emit(
                MessageType.PROVISIONAL_TEXT,
                ProvisionalTextPayload(sid, provisionalSeq, text.take(ServerProtocol.MAX_PROVISIONAL_CHARS)),
            )
        }

        override fun onCommit(snapshot: String, perf: CommitPerf) {
            val sid = activeSessionId() ?: return
            commitSeq += 1
            emit(MessageType.COMMIT_TEXT, CommitTextPayload(sid, commitSeq, snapshot, perf))
        }
    }

    /** Handle one decoded inbound message. */
    fun onMessage(message: ServerMessage) {
        if (phase == Phase.CLOSED) return

        when (message.type) {
            MessageType.PAIR_HELLO -> onPairHello(message.payload as PairHelloPayload)
            MessageType.PAIR_DONE -> onPairDone(message.payload as PairDonePayload)
            MessageType.HELLO -> onHello(message.payload as HelloPayload)
            MessageType.PING -> onPing(message.payload as PingPayload)

            else -> {
                // Everything else requires an authenticated session (PROTOCOL.md §8: AUTH_REQUIRED).
                if (phase != Phase.READY) {
                    emitError(ErrorCode.AUTH_REQUIRED, "HELLO required before ${message.type.wire}", fatal = true)
                    return
                }
                when (message.type) {
                    MessageType.START_DICTATION -> onStartDictation(message.payload as StartDictationPayload)
                    MessageType.STOP_DICTATION -> onStopDictation(message.payload as StopDictationPayload)
                    MessageType.CANCEL_DICTATION -> onCancelDictation(message.payload as CancelDictationPayload)
                    MessageType.TAKEOVER -> onTakeover(message.payload as TakeoverPayload)
                    else -> Unit // A client-bound message arriving at the server is ignored (§3).
                }
            }
        }
    }

    /** The transport dropped: end any capture and stop accepting messages. */
    fun onTransportClosed() {
        if (sessionId != null) capture.cancel()
        sessionId = null
        sessionRequestId = null
        stopping = false
        phase = Phase.CLOSED
    }

    // --- Pairing ------------------------------------------------------------------------------

    private fun onPairHello(p: PairHelloPayload) {
        if (phase == Phase.READY) return
        // The lockout is the authority's (Chunk 2b); the engine only asks whether it is in force.
        if (pairing.lockoutRemainingMs() > 0) {
            emit(MessageType.PAIR_FAIL, PairFailPayload(PairFailReason.LOCKED, 0))
            return
        }
        if (p.protover != ServerProtocol.VERSION) {
            emit(MessageType.PAIR_FAIL, PairFailPayload(PairFailReason.VERSION, pairing.attemptsRemaining()))
            return
        }

        pendingDeviceId = p.deviceId
        val nonce = randomBase64(NONCE_BYTES)
        serverNonce = nonce
        emit(MessageType.PAIR_CHALLENGE, PairChallengePayload(nonce, pairing.serverSpki))

        scope.launch {
            when (pairing.approve(p.deviceId, p.name)) {
                PairingDecision.APPROVED -> {
                    val device = pairing.issue(p.deviceId, p.name)
                    pendingToken = device.token
                    emit(
                        MessageType.PAIR_RESPONSE,
                        PairResponsePayload(device.deviceId, identity.serverName, device.token),
                    )
                }
                PairingDecision.DENIED ->
                    emit(MessageType.PAIR_FAIL, PairFailPayload(PairFailReason.DENIED, pairing.attemptsRemaining()))
                PairingDecision.LOCKED ->
                    emit(MessageType.PAIR_FAIL, PairFailPayload(PairFailReason.LOCKED, 0))
            }
        }
    }

    private fun onPairDone(p: PairDonePayload) {
        val token = pendingToken
        val nonce = serverNonce
        if (token == null || nonce == null || !PairingProof.matches(token, nonce, p.proof)) {
            pairing.recordFailedProof()
            emit(MessageType.PAIR_FAIL, PairFailPayload(PairFailReason.DENIED, pairing.attemptsRemaining()))
            return
        }
        // Pairing alone opens nothing (PROTOCOL.md §4): pinned TLS + HELLO still have to complete.
        phase = Phase.AWAITING_AUTH
    }

    // --- Authentication -----------------------------------------------------------------------

    private fun onHello(p: HelloPayload) {
        // A connection authenticates once; a repeat HELLO is ignored rather than re-running Keystore
        // auth and re-emitting WELCOME.
        if (phase == Phase.READY) return
        if (p.protover != ServerProtocol.VERSION) {
            emit(MessageType.AUTH_FAIL, AuthFailPayload(AuthFailReason.PROTO_VERSION))
            return
        }
        val device = pairing.authenticate(p.deviceId, p.token)
        if (device == null) {
            emit(MessageType.AUTH_FAIL, AuthFailPayload(AuthFailReason.BAD_TOKEN))
            return
        }
        authenticatedDevice = device
        phase = Phase.READY
        onAuthenticated(device)
        emit(
            MessageType.WELCOME,
            WelcomePayload(
                protover = ServerProtocol.VERSION,
                deviceId = identity.deviceId,
                serverName = identity.serverName,
                capabilities = identity.capabilities,
                maxFrameBytes = ServerProtocol.MAX_FRAME_BYTES,
            ),
        )
        // CHUNK-8: a freshly authenticated client learns immediately whether the one pipeline is
        // free, reserved by a phone-local surface, or held by a desktop session.
        emit(MessageType.SERVER_STATE, availability())
    }

    // --- Dictation session --------------------------------------------------------------------

    private fun onStartDictation(p: StartDictationPayload) {
        val active = sessionId
        if (active != null) {
            // Idempotent on requestId (PROTOCOL.md §6); a different request while busy is refused.
            if (p.requestId == sessionRequestId) {
                emit(MessageType.START_ACK, StartAckPayload(active))
            } else {
                emit(MessageType.SESSION_BUSY, busyPayload(SessionArbiter.Owner.SERVER))
            }
            return
        }

        when (val outcome = capture.begin(listener)) {
            StartOutcome.Started -> {
                val sid = newId()
                sessionId = sid
                sessionRequestId = p.requestId
                stopping = false
                provisionalSeq = 0
                commitSeq = 0
                emit(MessageType.START_ACK, StartAckPayload(sid))
            }
            is StartOutcome.Busy -> emit(MessageType.SESSION_BUSY, busyPayload(outcome.holder))
            is StartOutcome.Failed -> emitError(ErrorCode.ENGINE_UNAVAILABLE, outcome.reason, fatal = true)
        }
    }

    private fun onStopDictation(p: StopDictationPayload) {
        val sid = sessionId
        if (sid == null || p.sessionId != sid) {
            emitSessionUnknown(p.sessionId)
            return
        }
        if (stopping) return
        stopping = true
        emitState(PipelineState.PROCESSING)
        scope.launch {
            val result = try {
                capture.finish()
            } catch (e: Throwable) {
                // A throwing finish must still release the session, or the connection wedges with a
                // sessionId that can never be stopped and the arbiter stays held.
                emitError(ErrorCode.INTERNAL, e.message ?: "finish failed", fatal = false)
                endSession(sid, ReleaseReason.ERROR)
                return@launch
            }
            // A capture ERROR may have released the session while finish() was in flight; do not
            // deliver text for a session that no longer exists.
            if (sessionId != sid) return@launch
            emit(
                MessageType.UTTERANCE_DONE,
                UtteranceDonePayload(sid, result.deliveryText, result.rawText, UtteranceDoneReason.FINAL),
            )
            endSession(sid, ReleaseReason.STOPPED)
        }
    }

    private fun onCancelDictation(p: CancelDictationPayload) {
        val sid = sessionId
        if (sid == null || p.sessionId != sid) {
            emitSessionUnknown(p.sessionId)
            return
        }
        capture.cancel()
        endSession(sid, ReleaseReason.CANCELLED)
    }

    private fun onTakeover(p: TakeoverPayload) {
        // Cross-connection takeover needs a service-level session registry (Chunk 1b); a connection
        // cannot take over a session it does not hold.
        if (p.sessionId != sessionId) {
            emitSessionUnknown(p.sessionId)
        } else {
            emit(MessageType.SESSION_BUSY, busyPayload(SessionArbiter.Owner.SERVER))
        }
    }

    private fun endSession(sid: String, reason: ReleaseReason) {
        if (sessionId != sid) return
        emitState(PipelineState.IDLE)
        emit(MessageType.SESSION_RELEASED, SessionReleasedPayload(sid, reason))
        sessionId = null
        sessionRequestId = null
        stopping = false
    }

    private fun busyPayload(holder: SessionArbiter.Owner): SessionBusyPayload = when (holder) {
        // The phone's own pill holds the arbiter.
        SessionArbiter.Owner.PILL,
        // The transcribe screen's own capture (live mic or file replay) holds it (D-126).
        SessionArbiter.Owner.TRANSCRIBE,
        ->
            SessionBusyPayload(identity.deviceId, identity.serverName, canTakeover = false)
        // A different desktop: identifying it needs the service registry (Chunk 1b).
        SessionArbiter.Owner.SERVER ->
            SessionBusyPayload("", "another desktop", canTakeover = false)
    }

    // --- Ops ----------------------------------------------------------------------------------

    private fun onPing(p: PingPayload) {
        onHeartbeat()
        emit(MessageType.PONG, PongPayload(p.clientTs, now()))
    }

    // --- Emission -----------------------------------------------------------------------------

    private fun activeSessionId(): String? = if (stopping) null else sessionId

    private fun emitState(state: PipelineState) {
        val sid = sessionId ?: return
        emit(MessageType.STATE_CHANGED, StateChangedPayload(sid, state))
    }

    private fun emitError(code: ErrorCode, message: String, fatal: Boolean) {
        emit(MessageType.ERROR, ErrorPayload(code, message, fatal, sessionId))
    }

    private fun emitSessionUnknown(sid: String) {
        emit(
            MessageType.ERROR,
            ErrorPayload(ErrorCode.SESSION_UNKNOWN, "unknown session $sid", fatal = false, sessionId = sessionId),
        )
    }

    private fun emit(type: MessageType, payload: MessagePayload) {
        sink(ServerMessage(type, newId(), now(), payload))
    }

    private fun randomBase64(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private companion object {
        const val NONCE_BYTES = 16
    }
}
